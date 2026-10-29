package dev.tn3w.shelf.data

import android.Manifest.permission.INTERNET
import android.content.Context
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
private const val COVER_CACHE = "covers"
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
)

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
    val reduceMotion: Boolean = false,
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
    val readerBrightness: Float? = null,
    val offline: Boolean = false,
    val onlineCovers: Boolean = true,
    val authorImages: Boolean = true,
    val catalogueUpdates: Boolean = true,
    val checkUpdates: Boolean = true,
    val lastCatalogueCheck: Long = 0,
    val lastAppCheck: Long = 0,
    val availableUpdate: Release? = null,
    val catalogueSource: String = "",
    val coverSource: String = "",
    val dnbCoverSource: String = "",
    val digarCoverSource: String = "",
    val bookbitesCoverSource: String = "",
    val providers: Set<String> = emptySet(),
    val instantSource: String = "",
    val librarySort: LibrarySort = LibrarySort.Added,
    val libraryList: Boolean = false,
    val backupFolder: String = "",
) {
    val coverHost
        get() = coverSource.ifEmpty { COVERS }.trimEnd('/')

    val dnbCoverHost
        get() = dnbCoverSource.ifEmpty { DNB_COVERS }.trimEnd('/')

    val digarCoverHost
        get() = digarCoverSource.ifEmpty { DIGAR_COVERS }.trimEnd('/')

    val bookbitesCoverHost
        get() = bookbitesCoverSource.ifEmpty { BOOKBITES_COVERS }.trimEnd('/')
}

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
)

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

    val settings = state.map { it.settings }.shared()

    val habit = combine(reading, settings, dates()) { current, settings, today ->
        habitOf(current.activity, settings.dailyGoal, today)
    }.shared()

    private val booksDir
        get() = context.filesDir.resolve("books")

    fun bookFile(name: String) = booksDir.resolve(name)

    suspend fun deleteTemporaryFiles() = withContext(Dispatchers.IO) {
        booksDir.listFiles(File::isTemporary).orEmpty().forEach(File::delete)
        context.cacheDir.resolve(COVER_CACHE).deleteRecursively()
        context.cacheDir.listFiles { file -> file.name.startsWith(EXTRACTED_PREFIX) }
            .orEmpty()
            .forEach(File::deleteRecursively)
    }

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
        saveProgress(book.work, previous ?: Progress(name))
        place(book, Shelf.Reading)
        return true
    }

    suspend fun importFile(uri: Uri): Int? = withContext(Dispatchers.IO) {
        val fileName = runSuspendCatching { context.displayName(uri) }.getOrNull()
        if (fileName == null || !isReadable(fileName)) return@withContext null
        val temporary = File.createTempFile("import", ".tmp", booksDir.apply { mkdirs() })
        val work = context.readFrom(uri) { input ->
            temporary.outputStream().use { contentWork(input, it) }
        }
        val name = "$work.${extensionOf(fileName)}"
        if (work == null || !temporary.renameTo(bookFile(name))) {
            temporary.delete()
            return@withContext null
        }
        deleteOtherFormats(booksDir, work, keep = name)
        val metadata = readMetadata(bookFile(name))
        val title = metadata?.title ?: fileName.substringBeforeLast('.').replace('_', ' ')
        readingStore.change {
            it.copy(progress = importedProgress(it.progress, work, name))
        }
        val existing = saved.first().firstOrNull { it.work == work }
        val book = bookOf(work, title, metadata?.author.orEmpty(), existing?.cover ?: 0)
        place(book, existing?.shelf ?: Shelf.Reading)
        work
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

    suspend fun deleteBook(work: Int, keepShelves: Boolean = false) =
        withContext(Dispatchers.IO) {
            progress.first()[work]?.let { bookFile(it.file).delete() }
            readingStore.change { it.copy(progress = it.progress - work) }
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
        val placed = shelf?.let(book::toSaved)?.copy(
            rating = previous?.rating ?: 0,
            isbn = previous?.isbn.orEmpty(),
            added = previous?.added?.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        library.copy(entries = placed?.let { others + it } ?: others)
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
        readingStore.change { reading ->
            val today = LocalDate.now().toString()
            val pages = (reading.activity[today] ?: 0) + pagesRead
            val activity = reading.activity + (today to pages)
            val positions = reading.progress + (work to progress)
            reading.copy(positions, if (pagesRead > 0) activity else reading.activity)
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
)

internal fun LibraryState.merge(imported: Backup) = copy(
    entries = newestPerWork(entries + imported.entries),
    dismissed = dismissed + imported.dismissed,
    settings = restoredSettings(imported.settings, settings),
)

internal fun ReadingState.merge(imported: Backup) = copy(
    progress = imported.progress.filterValues { BOOK_FILE.matches(it.file) } + progress,
    activity = maxPerDay(imported.activity, activity),
)

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
    authorImages = current.authorImages,
    catalogueUpdates = current.catalogueUpdates,
    checkUpdates = current.checkUpdates,
    lastCatalogueCheck = current.lastCatalogueCheck,
    lastAppCheck = current.lastAppCheck,
    availableUpdate = current.availableUpdate,
    catalogueSource = current.catalogueSource,
    coverSource = current.coverSource,
    dnbCoverSource = current.dnbCoverSource,
    digarCoverSource = current.digarCoverSource,
    bookbitesCoverSource = current.bookbitesCoverSource,
    providers = current.providers,
    instantSource = current.instantSource,
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
)

private fun newestPerWork(entries: List<Saved>) =
    entries.groupBy { it.work }.map { (_, saved) -> saved.maxBy { it.updated } }

private fun maxPerDay(imported: Map<String, Int>, current: Map<String, Int>) =
    (imported.keys + current.keys).associateWith {
        maxOf(imported[it] ?: 0, current[it] ?: 0)
    }
