package dev.tn3w.shelf.data

import android.Manifest.permission.INTERNET
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.buildChildDocumentsUriUsingTree
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.createDocument
import android.provider.DocumentsContract.deleteDocument
import android.provider.DocumentsContract.getTreeDocumentId
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tn3w.shelf.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val RECENT_LIMIT = 8
private const val LIBRARY_FILE = "library.json"
private const val READING_FILE = "reading.json"
private const val LEGACY_FILE = "datastore/library.preferences_pb"
private const val BACKUP_ENTRY = "library.json"
private const val BOOKS_PREFIX = "books/"
private const val KEPT_BACKUPS = 7
private val AUTOMATIC_BACKUP = Regex("""shelf-\d{4}-\d{2}-\d{2}\.zip""")
private val BOOK_FILE = Regex("""(-?\d+)(\.\w+|-cover-\d+)""")
val FONT_SCALES = 0.7f..1.8f
val SPACING_RANGE = 1.35f..1.8f
val MARGIN_RANGE = 16..44

enum class Shelf(@StringRes val label: Int) {
    Reading(R.string.reading),
    Want(R.string.want_to_read),
    Read(R.string.finished),
}

@Serializable
data class Saved(
    val work: Int,
    val shelf: Shelf,
    val updated: Long,
    val title: String = "",
    val author: String = "",
    val cover: Int = 0,
    val rating: Int = 0,
    val isbn: String = "",
    val added: Long = 0,
    val libraryCover: LibraryCover? = null,
    val started: Long = 0,
    val finished: Long = 0,
    val review: String = "",
    val notes: String = "",
    val lists: List<String> = emptyList(),
)

fun listName(name: String) = name.replace(',', ' ').trim().replace(Regex("""\s+"""), " ")

fun withList(lists: List<String>, name: String) =
    (lists + listName(name)).filter(String::isNotEmpty).distinctBy(String::lowercase)

fun List<String>.hasList(name: String) = any { it.equals(name, ignoreCase = true) }

private fun List<String>.without(name: String) =
    filterNot { it.equals(name, ignoreCase = true) }

fun toggledList(lists: List<String>, name: String) =
    if (lists.hasList(name)) lists.without(name) else withList(lists, name)

fun List<Saved>.listNames() = flatMap { it.lists }
    .distinctBy(String::lowercase)
    .sortedBy(String::lowercase)

data class BookList(val name: String, val books: List<Saved>)

fun List<Saved>.bookLists() =
    listNames().map { name -> BookList(name, filter { it.lists.hasList(name) }) }

fun List<Saved>.commonLists() = listNames().filter { name ->
    isNotEmpty() && all { it.lists.hasList(name) }
}

internal fun List<Saved>.toggledList(works: Collection<Int>, name: String): List<Saved> {
    val inAll = filter { it.work in works }.commonLists().hasList(name)
    return map { saved ->
        if (saved.work !in works) return@map saved
        val lists = if (inAll) saved.lists.without(name) else withList(saved.lists, name)
        saved.copy(lists = lists)
    }
}

fun listText(name: String, books: List<Saved>) = buildString {
    appendLine(name)
    books.forEach { book ->
        append("\n• ").append(book.title)
        if (book.author.isNotBlank()) append(" — ").append(book.author)
    }
}

internal fun List<Saved>.renamedList(from: String, to: String?) = map { saved ->
    if (!saved.lists.hasList(from)) return@map saved
    val others = saved.lists.without(from)
    saved.copy(lists = to?.let { withList(others, it) } ?: others)
}

val Saved.finishedAt
    get() = finished.takeIf { it > 0 } ?: updated

fun Saved.dated() = if (shelf == Shelf.Read) copy(finished = finishedAt) else this

internal fun placed(book: Book, shelf: Shelf, previous: Saved?, now: Long): Saved {
    val kept = previous?.dated()
    val started = when {
        shelf == Shelf.Reading && kept?.shelf != Shelf.Reading -> now
        else -> kept?.started ?: 0
    }
    val finished = when {
        shelf != Shelf.Read -> kept?.finished ?: 0
        kept?.shelf == Shelf.Read -> kept.finished
        else -> now
    }
    return book.toSaved(shelf).copy(
        updated = now,
        rating = kept?.rating ?: 0,
        isbn = kept?.isbn.orEmpty(),
        review = kept?.review.orEmpty(),
        notes = kept?.notes.orEmpty(),
        lists = kept?.lists.orEmpty(),
        added = kept?.added?.takeIf { it > 0 } ?: now,
        started = started,
        finished = finished,
    )
}

internal fun List<Saved>.edited(work: Int, now: Long, change: (Saved) -> Saved) = map {
    if (it.work == work) change(it.dated()).copy(updated = now) else it
}

const val MAX_DAILY_GOAL = 500

@Serializable
data class Progress(
    val file: String,
    val section: Int = 0,
    val offset: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
) {
    val fraction
        get() = if (pages > 0) (page + 1f) / pages else null
}

@Serializable
data class Bookmark(
    val section: Int = 0,
    val offset: Int = 0,
    val page: Int = 0,
    val excerpt: String = "",
)

enum class ThemeMode(@StringRes val label: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark),
}

enum class PageColor(@StringRes val label: Int) {
    Theme(R.string.page_app),
    Paper(R.string.page_paper),
    Night(R.string.page_night),
}

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val language: String = "",
    val theme: ThemeMode = ThemeMode.System,
    val blackTheme: Boolean = false,
    val wallpaperColors: Boolean = true,
    val eInk: Boolean = false,
    val dailyGoal: Int = 10,
    val fontScale: Float = 1f,
    val lineSpacing: Float = 1.55f,
    val margin: Int = 28,
    val serif: Boolean = true,
    val justify: Boolean = false,
    val pageColor: PageColor = PageColor.Theme,
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = false,
    val brightnessSwipe: Boolean = true,
    val speechRate: Float = 1f,
    val speechVoice: String = "",
    val characterVoices: Boolean = true,
    val readerBrightness: Float? = null,
    val offline: Boolean = false,
    val onlineCovers: Boolean = true,
    val coverProxy: Boolean = true,
    val coverProxyUrl: String = "",
    val catalogueUpdates: Boolean = true,
    val checkUpdates: Boolean = true,
    val lastCatalogueCheck: Long = 0,
    val lastAppCheck: Long = 0,
    val availableUpdate: Release? = null,
    val catalogueSource: String = "",
    val providers: Set<String> = emptySet(),
    val librarySort: LibrarySort = LibrarySort.Added,
    val libraryList: Boolean = false,
    val backupFolder: String = "",
)

fun storedLanguage(chosen: String, system: String) = if (chosen == system) "" else chosen

fun networkPermitted(context: Context) =
    context.checkSelfPermission(INTERNET) == PackageManager.PERMISSION_GRANTED

fun Settings.isOffline(context: Context) = offline || !networkPermitted(context)

@Serializable
data class Backup(
    val entries: List<Saved> = emptyList(),
    val progress: Map<Int, Progress> = emptyMap(),
    val activity: Map<String, Int> = emptyMap(),
    val dismissed: Set<Int> = emptySet(),
    val settings: Settings = Settings(),
    val bookmarks: Map<Int, List<Bookmark>> = emptyMap(),
    val highlights: Map<Int, List<Highlight>> = emptyMap(),
)

data class Habit(
    val goal: Int,
    val today: Int,
    val streak: Int,
    val week: List<Int>,
    val day: LocalDate,
) {
    val done
        get() = today >= goal
}

internal fun habitOf(
    activity: Map<String, Int>,
    dailyGoal: Int,
    today: LocalDate = LocalDate.now(),
): Habit {
    val goal = dailyGoal.coerceAtLeast(1)
    fun pages(day: LocalDate) = activity[day.toString()] ?: 0
    val start = if (pages(today) >= goal) today else today.minusDays(1)
    val streak = generateSequence(start) { it.minusDays(1) }
        .takeWhile { pages(it) >= goal }
        .count()
    val week = (6 downTo 0).map { pages(today.minusDays(it.toLong())) }
    return Habit(goal, pages(today), streak, week, today)
}

internal fun dates(
    now: () -> LocalDate = LocalDate::now,
    interval: Duration = 1.minutes,
) = flow {
    while (true) {
        emit(now())
        delay(interval)
    }
}.distinctUntilChanged()

fun Book.toSaved(shelf: Shelf) = System.currentTimeMillis().let {
    Saved(work, shelf, it, title, author, cover, added = it, libraryCover = libraryCover)
}

fun Saved.toBook() = bookOf(work, title, author, cover, libraryCover)

private fun bookOf(
    work: Int,
    title: String,
    author: String,
    cover: Int = 0,
    libraryCover: LibraryCover? = null,
) = Book(
    work,
    title,
    authors = listOf(Author(0, author)),
    cover = cover,
    libraryCover = libraryCover,
)

typealias BookFinder = (title: String, author: String) -> Book?

val Book.isLocal
    get() = work < 0

private fun localWork(hash: Int) = -1 - (hash and Int.MAX_VALUE)

fun ownBook(title: String, author: String) =
    bookOf(localWork((title + author).hashCode()), title, author)

private val TEMPORARY_EXTENSIONS = setOf("part", "tmp")

private fun File.isTemporary() = extension in TEMPORARY_EXTENSIONS

internal fun contentWork(input: InputStream, output: OutputStream): Int {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).copyTo(output)
    return localWork(ByteBuffer.wrap(digest.digest()).int)
}

fun Context.displayName(uri: Uri): String {
    val name = contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> cursor.takeIf { it.moveToFirst() }?.getString(0) }
        ?: uri.lastPathSegment.orEmpty()
    if ('.' in name) return name
    val type = contentResolver.getType(uri) ?: return name
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    return if (extension == null) name else "$name.$extension"
}

suspend fun <T> Context.readFrom(uri: Uri, read: (InputStream) -> T): T? =
    withContext(Dispatchers.IO) {
        runSuspendCatching { contentResolver.openInputStream(uri)?.use(read) }.getOrNull()
    }

suspend fun Context.writeTo(uri: Uri, write: (OutputStream) -> Unit) =
    withContext(Dispatchers.IO) {
        runSuspendCatching { contentResolver.openOutputStream(uri)?.use(write) != null }
            .getOrDefault(false)
    }

@Serializable
data class LibraryState(
    val entries: List<Saved> = emptyList(),
    val recent: List<String> = emptyList(),
    val dismissed: Set<Int> = emptySet(),
    val settings: Settings = Settings(),
)

@Serializable
data class ReadingState(
    val progress: Map<Int, Progress> = emptyMap(),
    val activity: Map<String, Int> = emptyMap(),
    val bookmarks: Map<Int, List<Bookmark>> = emptyMap(),
    val highlights: Map<Int, List<Highlight>> = emptyMap(),
    val audiobooks: Map<Int, Audiobook> = emptyMap(),
    val listening: Set<Int> = emptySet(),
)

internal fun ReadingState.listeningTo(work: Int, listening: Boolean) =
    copy(listening = if (listening) this.listening + work else this.listening - work)

internal class JsonSerializer<T>(
    override val defaultValue: T,
    private val serializer: KSerializer<T>,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (error: IllegalArgumentException) {
        throw CorruptionException("unreadable library data", error)
    }

    override suspend fun writeTo(t: T, output: OutputStream) =
        output.write(json.encodeToString(serializer, t).toByteArray())
}

private class LegacyMigration<T>(
    private val context: Context,
    private val name: String,
    private val convert: (Preferences) -> T,
) : DataMigration<T> {
    private val legacy = context.filesDir.resolve(LEGACY_FILE)

    override suspend fun shouldMigrate(currentData: T) =
        legacy.exists() && !context.dataStoreFile(name).exists()

    override suspend fun migrate(currentData: T) =
        legacy.inputStream().use { convert(PreferencesFileSerializer.readFrom(it)) }

    override suspend fun cleanUp() {
        val stores = listOf(LIBRARY_FILE, READING_FILE).map(context::dataStoreFile)
        if (stores.all(File::exists)) legacy.delete()
    }
}

private inline fun <reified T> Preferences.legacy(name: String, fallback: T): T =
    this[stringPreferencesKey(name)]
        ?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
        ?: fallback

internal fun Preferences.toLibraryState() = LibraryState(
    legacy("entries", emptyList()),
    legacy("recent", emptyList()),
    legacy("dismissed", emptySet()),
    legacy("settings", Settings()),
)

internal fun Preferences.toReadingState() =
    ReadingState(legacy("progress", emptyMap()), legacy("activity", emptyMap()))

private val Context.libraryStore by dataStore(
    LIBRARY_FILE,
    JsonSerializer(LibraryState(), LibraryState.serializer()),
    produceMigrations = {
        listOf(LegacyMigration(it, LIBRARY_FILE, Preferences::toLibraryState))
    },
)

private val Context.readingStore by dataStore(
    READING_FILE,
    JsonSerializer(ReadingState(), ReadingState.serializer()),
    produceMigrations = {
        listOf(LegacyMigration(it, READING_FILE, Preferences::toReadingState))
    },
)

private fun <T> Flow<T>.orDefault(default: T) = catch {
    if (it !is IOException) throw it
    emit(default)
}

private suspend fun <T> DataStore<T>.change(transform: (T) -> T) {
    try {
        updateData { transform(it) }
    } catch (error: CorruptionException) {
        Log.w("Library", "kept unreadable data unchanged", error)
    }
}

internal fun importedProgress(progress: Map<Int, Progress>, work: Int, name: String) =
    progress + (work to (progress[work]?.copy(file = name) ?: Progress(name)))

private const val MAX_LISTENED_TURN = 3

internal fun listenedPages(before: Progress?, after: Progress): Int {
    val turned = after.page - (before?.page ?: return 0)
    return if (turned in 1..MAX_LISTENED_TURN) turned else 0
}

private fun Progress.isBefore(other: Progress) =
    compareValuesBy(this, other, Progress::section, Progress::offset) < 0

internal fun keptPosition(stored: Progress?, start: Progress, next: Progress?): Progress {
    val onPage = stored != null && stored.file == start.file && !stored.isBefore(start) &&
        (next == null || stored.isBefore(next))
    return if (onPage) stored.copy(page = start.page, pages = start.pages) else start
}

private fun ReadingState.withProgress(work: Int, progress: Progress, pagesRead: Int) =
    copy(
        progress = this.progress + (work to progress),
        activity = if (pagesRead > 0) activity.addedToday(pagesRead) else activity,
    )

private fun Map<String, Int>.addedToday(pages: Int): Map<String, Int> {
    val today = LocalDate.now().toString()
    return this + (today to (this[today] ?: 0) + pages)
}

internal fun deleteOtherFormats(directory: File, work: Int, keep: String) = directory
    .listFiles { file -> file.name != keep && file.nameWithoutExtension == "$work" }
    .orEmpty()
    .forEach { it.delete() }

class Library(private val context: Context, private val scope: CoroutineScope) {
    private val libraryStore = context.libraryStore
    private val readingStore = context.readingStore
    private val state = libraryStore.data.orDefault(LibraryState())
    private val reading = readingStore.data.orDefault(ReadingState())

    private fun <T> Flow<T>.shared() = distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val saved = state.map { it.entries.sortedByDescending(Saved::updated) }.shared()

    val recentSearches = state.map { it.recent }.shared()

    val dismissed = state.map { it.dismissed }.shared()

    val progress = reading.map { it.progress }.shared()

    val bookmarks = reading.map { it.bookmarks }.shared()

    val highlights = reading.map { it.highlights }.shared()

    val audiobooks = reading.map { it.audiobooks }.shared()

    val activity = reading.map { it.activity }.shared()

    val settings = state.map { it.settings }.shared()

    val habit = combine(activity, settings, dates()) { activity, settings, today ->
        habitOf(activity, settings.dailyGoal, today)
    }.shared()

    private val booksDir
        get() = context.filesDir.resolve("books")

    fun bookFile(name: String) = booksDir.resolve(name)

    suspend fun deleteTemporaryFiles() = withContext(Dispatchers.IO) {
        booksDir.listFiles(File::isTemporary).orEmpty().forEach(File::delete)
        context.cacheDir.listFiles { file -> file.name.startsWith(EXTRACTED_PREFIX) }
            .orEmpty()
            .forEach(File::deleteRecursively)
    }

    suspend fun progressOf(work: Int) = reading.first().progress[work]

    suspend fun hasFile(progress: Progress?) = withContext(Dispatchers.IO) {
        progress != null && bookFile(progress.file).exists()
    }

    suspend fun importBook(book: Book, uri: Uri): Boolean {
        val fileName = withContext(Dispatchers.IO) { context.displayName(uri) }
        if (!isReadable(fileName)) return false
        val name = "${book.work}.${extensionOf(fileName)}"
        return attach(book, name) { checkNotNull(copy(uri, it)) }
    }

    suspend fun download(book: Book, offer: Offer, onProgress: (Float) -> Unit) =
        attach(book, "${book.work}.${offer.format}") { fetchBook(offer, it, onProgress) }

    suspend fun download(
        book: Book,
        client: CatalogClient,
        file: Acquisition,
        onProgress: (Float) -> Unit,
    ) = attach(book, "${book.work}.${file.format}") {
        client.download(file, it, onProgress)
    }

    private suspend fun attach(
        book: Book,
        name: String,
        write: suspend (File) -> Unit,
    ): Boolean {
        val written = withContext(Dispatchers.IO) {
            val target = bookFile(name).apply { parentFile?.mkdirs() }
            runSuspendCatching { replaceFile(target) { write(it) } }.isSuccess
        }
        if (!written) return false
        withContext(Dispatchers.IO) {
            deleteOtherFormats(booksDir, book.work, keep = name)
        }
        val previous = progress.first()[book.work]?.takeIf { it.file == name }
        if (previous == null) forgetMarks(book.work)
        saveProgress(book.work, previous ?: Progress(name))
        place(book, Shelf.Reading)
        return true
    }

    suspend fun importFile(uri: Uri, find: BookFinder): Int? =
        withContext(Dispatchers.IO) {
            val fileName = runSuspendCatching { context.displayName(uri) }.getOrNull()
            if (fileName != null && extensionOf(fileName) in AUDIO_EXTENSIONS) {
                return@withContext importAudio(uri, find)
            }
            if (fileName == null || !isReadable(fileName)) return@withContext null
            importBook(fileName, find = find) { output ->
                context.readFrom(uri) { input -> contentWork(input, output) }
            }
        }

    suspend fun importDownload(
        file: File,
        title: String,
        author: String,
        find: BookFinder,
    ) = withContext(Dispatchers.IO) {
        importBook("$title.${file.extension}", author, find) { output ->
            file.inputStream().use { contentWork(it, output) }
        }
    }

    private suspend fun importBook(
        fileName: String,
        fallbackAuthor: String = "",
        find: BookFinder,
        write: suspend (OutputStream) -> Int?,
    ): Int? {
        val temporary = File.createTempFile("import", ".tmp", booksDir.apply { mkdirs() })
        val hashed = temporary.outputStream().use { write(it) }
        val extension = extensionOf(fileName)
        val written = bookFile("$hashed.$extension")
        if (hashed == null || !temporary.renameTo(written)) {
            temporary.delete()
            return null
        }
        val metadata = readMetadata(written)
        val title = metadata?.title ?: fileName.substringBeforeLast('.').replace('_', ' ')
        val author = metadata?.author ?: fallbackAuthor
        val entries = saved.first()
        val known = entries.firstOrNull { it.work == hashed }
        val book = find(title, author)
            ?: known?.let { bookOf(hashed, title, author, it.cover) }
            ?: bookOf(hashed, title, author)
        val name = "${book.work}.$extension"
        if (book.work != hashed && !written.renameTo(bookFile(name))) {
            written.delete()
            return null
        }
        if (book.work != hashed && known != null) deleteBook(hashed)
        deleteOtherFormats(booksDir, book.work, keep = name)
        readingStore.change {
            it.copy(progress = importedProgress(it.progress, book.work, name))
        }
        val shelf = entries.firstOrNull { it.work == book.work }?.shelf
        place(book, shelf ?: Shelf.Reading)
        return book.work
    }

    private suspend fun importAudio(uri: Uri, find: BookFinder): Int? {
        val files = listOfNotNull(keptAudio(uri)).ifEmpty { return null }
        val title = titleOf(files)
        val author = authorOf(files)
        val match = matchingBook(saved.first(), title, author)
        val book = match?.toBook() ?: find(title, author) ?: ownBook(title, author)
        if (match == null) place(book, Shelf.Reading)
        storeAudio(book.work, files)
        return book.work
    }

    suspend fun attachAudio(work: Int, uris: List<Uri>): Boolean {
        val files = withContext(Dispatchers.IO) { uris.mapNotNull(::keptAudio) }
        if (files.isEmpty()) return false
        storeAudio(work, files)
        return true
    }

    private suspend fun storeAudio(work: Int, files: List<AudioFile>) {
        val previous = audiobooks.first()[work]?.tracks.orEmpty().map { it.uri }
        release(previous - files.map { it.uri }.toSet())
        readingStore.change {
            it.copy(audiobooks = it.audiobooks + (work to audiobookOf(files)))
        }
    }

    suspend fun detachAudio(work: Int) {
        release(audiobooks.first()[work]?.tracks.orEmpty().map { it.uri })
        readingStore.change { it.copy(audiobooks = it.audiobooks - work) }
    }

    suspend fun saveAudioPosition(work: Int, track: Int, millis: Long) =
        readingStore.change { state ->
            val book = state.audiobooks[work] ?: return@change state
            val moved = book.copy(track = track, millis = millis)
            state.copy(audiobooks = state.audiobooks + (work to moved))
        }

    private val audioDir get() = context.filesDir.resolve("audio")

    private fun keptAudio(uri: Uri): AudioFile? {
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        val playable = if (kept) uri else copiedAudio(uri) ?: return null
        val file = context.audioFile(playable)
        if (file == null) release(listOf(playable.toString()))
        return file
    }

    private fun copiedAudio(uri: Uri): Uri? {
        val name = context.displayName(uri).replace('/', '_')
        val target = audioDir.resolve(UUID.randomUUID().toString()).resolve(name)
        target.parentFile?.mkdirs()
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
        if (copied.isSuccess) return Uri.fromFile(target)
        target.parentFile?.deleteRecursively()
        return null
    }

    private fun release(uris: Collection<String>) =
        uris.map(String::toUri).forEach { uri ->
            val copy = uri.path?.let(::File)?.takeIf { uri.scheme == "file" }
            if (copy != null && copy.startsWith(audioDir)) {
                copy.parentFile?.deleteRecursively()
                return@forEach
            }
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

    private fun extensionOf(fileName: String) = File(fileName).extension.lowercase()

    private fun isReadable(fileName: String) =
        extensionOf(fileName) in READABLE_EXTENSIONS

    fun coverFile(book: Book) = bookFile("${book.work}-cover-${book.cover}")

    suspend fun saveCover(work: Int, uri: Uri): Int? = withContext(Dispatchers.IO) {
        val cover = Random.nextInt(1, Int.MAX_VALUE)
        val name = "$work-cover-$cover"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (copy(uri, target) == null) {
            target.delete()
            return@withContext null
        }
        deleteCovers(work, keep = name)
        cover
    }

    private fun deleteCovers(work: Int, keep: String = "") = booksDir
        .listFiles { file -> file.name.startsWith("$work-cover-") && file.name != keep }
        .orEmpty()
        .forEach { it.delete() }

    private suspend fun copy(uri: Uri, target: File) =
        context.readFrom(uri) { input -> target.outputStream().use(input::copyTo) }

    fun voiceCache(work: Int) = context.filesDir.resolve("voices/$work.json")

    suspend fun deleteBook(work: Int, keepShelves: Boolean = false) =
        withContext(Dispatchers.IO) {
            progress.first()[work]?.let { bookFile(it.file).delete() }
            detachAudio(work)
            voiceCache(work).delete()
            readingStore.change {
                it.copy(
                    progress = it.progress - work,
                    bookmarks = it.bookmarks - work,
                    highlights = it.highlights - work,
                    listening = it.listening - work,
                )
            }
            if (keepShelves) return@withContext
            deleteCovers(work)
            libraryStore.change { library ->
                library.copy(entries = library.entries.filter { it.work != work })
            }
        }

    suspend fun place(book: Book, shelf: Shelf?) = libraryStore.change { library ->
        val entries = library.entries
        val previous = entries.firstOrNull { it.work == book.work }
        val others = entries.filter { it.work != book.work }
        val now = System.currentTimeMillis()
        val entry = shelf?.let { placed(book, it, previous, now) }
        library.copy(entries = entry?.let { others + it } ?: others)
    }

    suspend fun edit(work: Int, change: (Saved) -> Saved) = libraryStore.change {
        it.copy(entries = it.entries.edited(work, System.currentTimeMillis(), change))
    }

    suspend fun toggleList(works: Collection<Int>, name: String) = libraryStore.change {
        it.copy(entries = it.entries.toggledList(works, name))
    }

    suspend fun renameList(from: String, to: String?) = libraryStore.change {
        it.copy(entries = it.entries.renamedList(from, to))
    }

    suspend fun importCsv(
        uri: Uri,
        loaded: Loaded?,
        onProgress: (Float) -> Unit,
    ): CsvReport? {
        val text =
            context.readFrom(uri) { it.readBytes().decodeToString() } ?: return null
        val books = csvBooks(text).ifEmpty { return null }
        val missing = mutableListOf<String>()
        val entries = withContext(Dispatchers.Default) {
            books.mapIndexed { index, row ->
                onProgress(index.toFloat() / books.size)
                val match = loaded?.catalogue?.bookOfIsbn(row.isbn)
                    ?: loaded?.searcher?.find(row.title, row.author)
                if (match == null) missing += row.title
                val book = match ?: ownBook(row.title, row.author)
                book.toSaved(row.shelf)
                    .copy(
                        updated = row.date,
                        rating = row.rating,
                        isbn = row.isbn,
                        added = row.date,
                        review = row.review,
                        notes = row.notes,
                        lists = row.lists,
                    )
            }
        }
        libraryStore.change { it.copy(entries = newestPerWork(it.entries + entries)) }
        return CsvReport(books.size, missing)
    }

    suspend fun exportCsv(uri: Uri): Boolean {
        val entries = saved.first()
        return context.writeTo(uri) { it.write(csvOf(entries).toByteArray()) }
    }

    suspend fun saveProgress(work: Int, progress: Progress, pagesRead: Int = 0) =
        readingStore.change { it.withProgress(work, progress, pagesRead) }

    suspend fun isListening(work: Int) = work in reading.first().listening

    suspend fun markListening(work: Int, listening: Boolean) =
        readingStore.change { it.listeningTo(work, listening) }

    suspend fun addPagesRead(pages: Int) = readingStore.change {
        it.copy(activity = it.activity.addedToday(pages))
    }

    suspend fun saveListened(work: Int, progress: Progress) = readingStore.change {
        it.withProgress(work, progress, listenedPages(it.progress[work], progress))
    }

    suspend fun changeBookmarks(work: Int, change: (List<Bookmark>) -> List<Bookmark>) =
        readingStore.change { it.copy(bookmarks = it.bookmarks.changed(work, change)) }

    suspend fun changeHighlights(
        work: Int,
        change: (List<Highlight>) -> List<Highlight>,
    ) = readingStore.change { it.copy(highlights = it.highlights.changed(work, change)) }

    private suspend fun forgetMarks(work: Int) = readingStore.change {
        it.copy(bookmarks = it.bookmarks - work, highlights = it.highlights - work)
    }

    suspend fun exportNotes(uri: Uri, format: NotesFormat, work: Int? = null): Boolean {
        val marks = highlights.first().filterKeys { work == null || it == work }
        val books = bookNotes(saved.first(), marks)
        return context.writeTo(uri) { it.write(format.render(books).toByteArray()) }
    }

    suspend fun updateSettings(change: (Settings) -> Settings) =
        libraryStore.change { it.copy(settings = change(it.settings)) }

    suspend fun remember(query: String) = libraryStore.change { library ->
        val trimmed = query.trim()
        val recent = listOf(trimmed) + library.recent.filter { it != trimmed }
        library.copy(recent = recent.take(RECENT_LIMIT))
    }

    suspend fun clearSearches() = libraryStore.change { it.copy(recent = emptyList()) }

    suspend fun redirect(alias: (Int) -> Int?) {
        val works =
            saved.first().map { it.work } + progress.first().keys + dismissed.first()
        if (works.none { alias(it) != null }) return
        withContext(Dispatchers.IO) { moveBookFiles(booksDir, alias) }
        libraryStore.change { it.redirect(alias) }
        readingStore.change { it.redirect(alias) }
    }

    suspend fun dismiss(work: Int) =
        libraryStore.change { it.copy(dismissed = it.dismissed + work) }

    suspend fun exportTo(uri: Uri): Boolean {
        val backup = backupOf(state.first(), reading.first())
        return context.writeTo(uri) { writeArchive(it, backup) }
    }

    suspend fun backUpToFolder(today: LocalDate = LocalDate.now()) =
        withContext(Dispatchers.IO) {
            val folder = settings.first().backupFolder.ifEmpty { return@withContext }
            val tree = folder.toUri()
            val resolver = context.contentResolver
            val backups = context.documentsIn(tree)
            val name = automaticBackupName(today)
            if (name in backups) return@withContext
            val parent = buildDocumentUriUsingTree(tree, getTreeDocumentId(tree))
            val created = createDocument(resolver, parent, "application/zip", name)
                ?: return@withContext
            if (!exportTo(created)) {
                deleteDocument(resolver, created)
                return@withContext
            }
            staleBackups(backups.keys + name).forEach {
                deleteDocument(resolver, backups.getValue(it))
            }
        }

    private fun writeArchive(output: OutputStream, backup: Backup) =
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry(BACKUP_ENTRY))
            archive.write(json.encodeToString(backup).toByteArray())
            archive.closeEntry()
            val files = booksDir.listFiles().orEmpty().filterNot(File::isTemporary)
            files.forEach { file ->
                archive.putNextEntry(ZipEntry("$BOOKS_PREFIX${file.name}"))
                file.inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }

    suspend fun importFrom(uri: Uri): Boolean {
        val backup = context.readFrom(uri) { readArchive(it, booksDir) } ?: return false
        libraryStore.change { it.merge(backup) }
        readingStore.change { it.merge(backup) }
        return true
    }
}

private fun Context.documentsIn(tree: Uri): Map<String, Uri> {
    val children = buildChildDocumentsUriUsingTree(tree, getTreeDocumentId(tree))
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
    return contentResolver.query(children, columns, null, null, null)?.use { cursor ->
        generateSequence { cursor.takeIf { it.moveToNext() } }.associate {
            it.getString(1) to buildDocumentUriUsingTree(tree, it.getString(0))
        }
    }.orEmpty()
}

internal fun automaticBackupName(day: LocalDate) = "shelf-$day.zip"

internal fun staleBackups(names: Collection<String>, kept: Int = KEPT_BACKUPS) =
    names.filter(AUTOMATIC_BACKUP::matches).sortedDescending().drop(kept)

internal fun readArchive(input: InputStream, directory: File) =
    ZipInputStream(input).use { archive ->
        if (archive.nextEntry?.name != BACKUP_ENTRY) return@use null
        val text = archive.readAtMost(MAX_ENTRY_BYTES).decodeToString()
        val backup = json.decodeFromString<Backup>(text)
        generateSequence { archive.nextEntry }
            .filter { it.name.startsWith(BOOKS_PREFIX) }
            .forEach { extractBook(directory, it.name, archive) }
        backup
    }

internal fun extractBook(
    directory: File,
    entry: String,
    input: InputStream,
    limit: Long = MAX_BOOK_BYTES,
) {
    val name = entry.removePrefix(BOOKS_PREFIX)
    if (!BOOK_FILE.matches(name)) return
    val target = directory.resolve(name).apply { parentFile?.mkdirs() }
    if (target.exists() || directory.usableSpace < limit) return
    replaceFile(target) { file ->
        file.outputStream().use { output ->
            copyWithProgress(input, output) { size ->
                check(size <= limit) { "$name is too large" }
            }
        }
    }
}

internal fun movedFile(name: String, alias: (Int) -> Int?): String {
    val (work, rest) = BOOK_FILE.matchEntire(name)?.destructured ?: return name
    val target = work.toIntOrNull()?.let(alias) ?: return name
    return "$target$rest"
}

internal fun moveBookFiles(directory: File, alias: (Int) -> Int?) =
    directory.listFiles().orEmpty().forEach { file ->
        val name = movedFile(file.name, alias)
        if (name != file.name) file.renameTo(directory.resolve(name))
    }

internal fun backupOf(library: LibraryState, reading: ReadingState) = Backup(
    library.entries,
    reading.progress,
    reading.activity,
    library.dismissed,
    library.settings,
    reading.bookmarks,
    reading.highlights,
)

internal fun LibraryState.merge(imported: Backup) = copy(
    entries = newestPerWork(entries + imported.entries),
    dismissed = dismissed + imported.dismissed,
    settings = restoredSettings(imported.settings, settings),
)

internal fun ReadingState.merge(imported: Backup) = copy(
    progress = imported.progress.filterValues { BOOK_FILE.matches(it.file) } + progress,
    activity = maxPerDay(imported.activity, activity),
    bookmarks = joined(bookmarks, imported.bookmarks) {
        Triple(it.section, it.offset, it.page)
    },
    highlights = joined(highlights, imported.highlights) {
        Triple(it.section, it.start, it.end)
    },
)

private fun <T> joined(
    current: Map<Int, List<T>>,
    imported: Map<Int, List<T>>,
    key: (T) -> Any,
) = (imported.keys + current.keys).associateWith { work ->
    (current[work].orEmpty() + imported[work].orEmpty()).distinctBy(key)
}

internal fun <T> Map<Int, List<T>>.changed(
    work: Int,
    change: (List<T>) -> List<T>,
): Map<Int, List<T>> {
    val marks = change(this[work].orEmpty())
    return if (marks.isEmpty()) this - work else this + (work to marks)
}

private fun restoredSettings(imported: Settings, current: Settings) = imported.copy(
    onboarded = current.onboarded,
    dailyGoal = imported.dailyGoal.coerceIn(0, MAX_DAILY_GOAL),
    language = imported.language.takeIf { it.isEmpty() || it in LANGUAGES }
        ?: current.language,
    fontScale = imported.fontScale.coerceIn(FONT_SCALES),
    lineSpacing = imported.lineSpacing.coerceIn(SPACING_RANGE),
    margin = imported.margin.coerceIn(MARGIN_RANGE),
    offline = current.offline,
    onlineCovers = current.onlineCovers,
    coverProxy = current.coverProxy,
    coverProxyUrl = current.coverProxyUrl,
    catalogueUpdates = current.catalogueUpdates,
    checkUpdates = current.checkUpdates,
    lastCatalogueCheck = current.lastCatalogueCheck,
    lastAppCheck = current.lastAppCheck,
    availableUpdate = current.availableUpdate,
    catalogueSource = current.catalogueSource,
    providers = current.providers,
    backupFolder = current.backupFolder,
)

private fun Int.moved(alias: (Int) -> Int?) = alias(this) ?: this

internal fun LibraryState.redirect(alias: (Int) -> Int?) = copy(
    entries = newestPerWork(entries.map { it.copy(work = it.work.moved(alias)) }),
    dismissed = dismissed.mapTo(HashSet()) { it.moved(alias) },
)

internal fun ReadingState.redirect(alias: (Int) -> Int?) = copy(
    progress = progress.entries.associate { (work, progress) ->
        work.moved(alias) to progress.copy(file = movedFile(progress.file, alias))
    },
    bookmarks = bookmarks.moved(alias),
    highlights = highlights.moved(alias),
    audiobooks = audiobooks.mapKeys { it.key.moved(alias) },
)

private fun <T> Map<Int, List<T>>.moved(alias: (Int) -> Int?) = entries
    .groupBy({ it.key.moved(alias) }, { it.value })
    .mapValues { (_, marks) -> marks.flatten() }

private fun newestPerWork(entries: List<Saved>) =
    entries.groupBy { it.work }.map { (_, saved) -> saved.maxBy { it.updated } }

private fun maxPerDay(imported: Map<String, Int>, current: Map<String, Int>) =
    (imported.keys + current.keys).associateWith {
        maxOf(imported[it] ?: 0, current[it] ?: 0)
    }
