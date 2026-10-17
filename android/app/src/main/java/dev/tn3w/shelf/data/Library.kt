package dev.tn3w.shelf.data

import android.Manifest.permission.INTERNET
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.tn3w.shelf.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val RECENT_LIMIT = 8
private val Context.dataStore by preferencesDataStore(
    "library",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
internal val ENTRIES = slot("entries", emptyList<Saved>())
internal val RECENT = slot("recent", emptyList<String>())
internal val PROGRESS = slot("progress", emptyMap<Int, Progress>())
internal val ACTIVITY = slot("activity", emptyMap<String, Int>())
internal val SETTINGS = slot("settings", Settings())
internal val DISMISSED = slot("dismissed", emptySet<Int>())
private const val BACKUP_ENTRY = "library.json"
private const val BOOKS_PREFIX = "books/"
private val BOOK_FILE = Regex("""-?\d+(\.\w+|-cover-\d+)""")
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
    val dailyGoal: Int = 10,
    val fontScale: Float = 1f,
    val lineSpacing: Float = 1.55f,
    val margin: Int = 28,
    val serif: Boolean = true,
    val justify: Boolean = false,
    val pageColor: PageColor = PageColor.Theme,
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = false,
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
    val providers: Set<String> = emptySet(),
    val instantSource: String = "",
    val librarySort: LibrarySort = LibrarySort.Added,
    val libraryList: Boolean = false,
) {
    val coverHost
        get() = coverSource.ifEmpty { COVERS }.trimEnd('/')

    val dnbCoverHost
        get() = dnbCoverSource.ifEmpty { DNB_COVERS }.trimEnd('/')

    val digarCoverHost
        get() = digarCoverSource.ifEmpty { DIGAR_COVERS }.trimEnd('/')
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
        .query(uri, null, null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.takeIf { it.moveToFirst() && column >= 0 }?.getString(column)
        }
        ?: uri.lastPathSegment.orEmpty()
    if ('.' in name) return name
    val type = contentResolver.getType(uri) ?: return name
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    return if (extension == null) name else "$name.$extension"
}

suspend fun <T> Context.readFrom(uri: Uri, read: (InputStream) -> T): T? =
    withContext(Dispatchers.IO) {
        runCatching { contentResolver.openInputStream(uri)?.use(read) }.getOrNull()
    }

suspend fun Context.writeTo(uri: Uri, write: (OutputStream) -> Unit) =
    withContext(Dispatchers.IO) {
        runCatching { contentResolver.openOutputStream(uri)?.use(write) != null }
            .getOrDefault(false)
    }

class Slot<T>(name: String, val fallback: T, private val serializer: KSerializer<T>) {
    val key = stringPreferencesKey(name)

    fun decode(text: String) =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    fun encode(value: T) = json.encodeToString(serializer, value)
}

private inline fun <reified T> slot(name: String, fallback: T) =
    Slot(name, fallback, serializer<T>())

operator fun <T> Preferences.get(slot: Slot<T>) =
    this[slot.key]?.let(slot::decode) ?: slot.fallback

internal fun <T> MutablePreferences.update(slot: Slot<T>, change: (T) -> T) {
    val stored = this[slot.key]
    val current = if (stored == null) slot.fallback else slot.decode(stored) ?: return
    this[slot.key] = slot.encode(change(current))
}

internal fun importedProgress(progress: Map<Int, Progress>, work: Int, name: String) =
    progress + (work to (progress[work]?.copy(file = name) ?: Progress(name)))

internal fun deleteOtherFormats(directory: File, work: Int, keep: String) = directory
    .listFiles { file -> file.name != keep && file.nameWithoutExtension == "$work" }
    .orEmpty()
    .forEach { it.delete() }

class Library(private val context: Context, private val scope: CoroutineScope) {
    private val store = context.dataStore

    private val data = store.data.catch {
        if (it !is IOException) throw it
        emit(emptyPreferences())
    }

    private fun <T> Flow<T>.shared() = distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val saved = data.map { it[ENTRIES].sortedByDescending(Saved::updated) }.shared()

    val recentSearches = data.map { it[RECENT] }.shared()

    val dismissed = data.map { it[DISMISSED] }.shared()

    val progress = data.map { it[PROGRESS] }.shared()

    val settings = data.map { it[SETTINGS] }.shared()

    val habit = combine(data, dates()) { preferences, today ->
        habitOf(preferences[ACTIVITY], preferences[SETTINGS].dailyGoal, today)
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
        attach(book, "${book.work}.${offer.format}") { target ->
            withContext(Dispatchers.IO) {
                fetchBook(offer, target) { progress ->
                    ensureActive()
                    onProgress(progress)
                }
            }
        }

    private suspend fun attach(
        book: Book,
        name: String,
        write: suspend (File) -> Unit,
    ): Boolean {
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (runCatching { replaceFile(target) { write(it) } }.isFailure) return false
        deleteOtherFormats(booksDir, book.work, keep = name)
        val previous = progress.first()[book.work]?.takeIf { it.file == name }
        saveProgress(book.work, previous ?: Progress(name))
        place(book, Shelf.Reading)
        return true
    }

    suspend fun importFile(uri: Uri): Int? {
        val fileName = withContext(Dispatchers.IO) {
            runCatching { context.displayName(uri) }.getOrNull()
        }
        if (fileName == null || !isReadable(fileName)) return null
        val temporary = File.createTempFile("import", ".tmp", booksDir.apply { mkdirs() })
        val work = context.readFrom(uri) { input ->
            temporary.outputStream().use { contentWork(input, it) }
        }
        val name = "$work.${extensionOf(fileName)}"
        if (work == null || !temporary.renameTo(bookFile(name))) {
            temporary.delete()
            return null
        }
        deleteOtherFormats(booksDir, work, keep = name)
        val metadata = withContext(Dispatchers.IO) { readMetadata(bookFile(name)) }
        val title = metadata?.title ?: fileName.substringBeforeLast('.').replace('_', ' ')
        edit(PROGRESS) { importedProgress(it, work, name) }
        val existing = saved.first().firstOrNull { it.work == work }
        val book = bookOf(work, title, metadata?.author.orEmpty(), existing?.cover ?: 0)
        place(book, existing?.shelf ?: Shelf.Reading)
        return work
    }

    private fun extensionOf(fileName: String) = File(fileName).extension.lowercase()

    private fun isReadable(fileName: String) =
        extensionOf(fileName) in READABLE_EXTENSIONS

    fun coverFile(book: Book) = bookFile("${book.work}-cover-${book.cover}")

    suspend fun saveCover(work: Int, uri: Uri): Int? {
        val cover = (System.currentTimeMillis() / 1000).toInt()
        val name = "$work-cover-$cover"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (copy(uri, target) == null) {
            target.delete()
            return null
        }
        deleteCovers(work, keep = name)
        return cover
    }

    private fun deleteCovers(work: Int, keep: String = "") = booksDir
        .listFiles { file -> file.name.startsWith("$work-cover-") && file.name != keep }
        .orEmpty()
        .forEach { it.delete() }

    private suspend fun copy(uri: Uri, target: File) =
        context.readFrom(uri) { input -> target.outputStream().use(input::copyTo) }

    suspend fun deleteBook(work: Int, keepShelves: Boolean = false) {
        progress.first()[work]?.let { bookFile(it.file).delete() }
        edit(PROGRESS) { it - work }
        if (keepShelves) return
        deleteCovers(work)
        edit(ENTRIES) { entries ->
            entries.filter { it.work != work }
        }
    }

    private suspend fun <T> edit(slot: Slot<T>, change: (T) -> T) =
        store.edit { it.update(slot, change) }

    suspend fun place(book: Book, shelf: Shelf?) = edit(ENTRIES) { entries ->
        val previous = entries.firstOrNull { it.work == book.work }
        val others = entries.filter { it.work != book.work }
        val placed = shelf?.let(book::toSaved)?.copy(
            rating = previous?.rating ?: 0,
            isbn = previous?.isbn.orEmpty(),
            added = previous?.added?.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        placed?.let { others + it } ?: others
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
        edit(ENTRIES) { newestPerWork(it + entries) }
        return CsvReport(books.size, missing)
    }

    suspend fun exportCsv(uri: Uri): Boolean {
        val entries = saved.first()
        return context.writeTo(uri) { it.write(csvOf(entries).toByteArray()) }
    }

    suspend fun saveProgress(work: Int, progress: Progress, pagesRead: Int = 0) =
        store.edit {
            it.update(PROGRESS) { all ->
                all + (work to progress)
            }
            if (pagesRead > 0) {
                it.update(ACTIVITY) { activity ->
                    val today = LocalDate.now().toString()
                    activity + (today to (activity[today] ?: 0) + pagesRead)
                }
            }
        }

    suspend fun updateSettings(change: (Settings) -> Settings) = edit(SETTINGS, change)

    suspend fun remember(query: String) = edit(RECENT) { recent ->
        val trimmed = query.trim()
        (listOf(trimmed) + recent.filter { it != trimmed }).take(RECENT_LIMIT)
    }

    suspend fun clearSearches() = store.edit { it.remove(RECENT.key) }

    suspend fun redirect(alias: (Int) -> Int?) {
        val works =
            saved.first().map { it.work } + progress.first().keys + dismissed.first()
        if (works.none { alias(it) != null }) return
        moveCovers(booksDir, alias)
        store.edit { it.redirect(alias) }
    }

    suspend fun dismiss(work: Int) = edit(DISMISSED) { it + work }

    suspend fun exportTo(uri: Uri): Boolean {
        val backup = data.first().toBackup()
        return context.writeTo(uri) { writeArchive(it, backup) }
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
        val backup = context.readFrom(uri, ::readArchive) ?: return false
        merge(backup)
        return true
    }

    private fun readArchive(input: InputStream) = ZipInputStream(input).use { archive ->
        var backup: Backup? = null
        generateSequence { archive.nextEntry }.forEach { entry ->
            when {
                entry.name == BACKUP_ENTRY -> backup = readBackup(archive)

                entry.name.startsWith(BOOKS_PREFIX) ->
                    extractBook(booksDir, entry.name, archive)
            }
        }
        backup
    }

    private fun readBackup(archive: ZipInputStream): Backup =
        json.decodeFromString(archive.readAtMost(MAX_ENTRY_BYTES).decodeToString())

    private suspend fun merge(backup: Backup) = store.edit { it.merge(backup) }
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

internal fun moveCovers(directory: File, alias: (Int) -> Int?) =
    directory.listFiles().orEmpty().forEach { file ->
        val work = file.name.substringBefore("-cover-", "").toIntOrNull()
        val target = work?.let(alias) ?: return@forEach
        val cover = file.name.substringAfter("-cover-")
        file.renameTo(directory.resolve("$target-cover-$cover"))
    }

private fun Preferences.toBackup() = Backup(
    this[ENTRIES],
    this[PROGRESS],
    this[ACTIVITY],
    this[DISMISSED],
    this[SETTINGS],
)

internal fun MutablePreferences.merge(imported: Backup) {
    update(ENTRIES) { newestPerWork(it + imported.entries) }
    val progress = imported.progress.filterValues { BOOK_FILE.matches(it.file) }
    update(PROGRESS) { progress + it }
    update(ACTIVITY) { maxPerDay(imported.activity, it) }
    update(DISMISSED) { it + imported.dismissed }
    update(SETTINGS) { restoredSettings(imported.settings, it) }
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
    providers = current.providers,
    instantSource = current.instantSource,
)

internal fun MutablePreferences.redirect(alias: (Int) -> Int?) {
    fun moved(work: Int) = alias(work) ?: work
    update(ENTRIES) { entries ->
        newestPerWork(entries.map { it.copy(work = moved(it.work)) })
    }
    update(PROGRESS) { all ->
        all.mapKeys { moved(it.key) }
    }
    update(DISMISSED) { dismissed ->
        dismissed.mapTo(HashSet(), ::moved)
    }
}

private fun newestPerWork(entries: List<Saved>) =
    entries.groupBy { it.work }.map { (_, saved) -> saved.maxBy { it.updated } }

private fun maxPerDay(imported: Map<String, Int>, current: Map<String, Int>) =
    (imported.keys + current.keys).associateWith {
        maxOf(imported[it] ?: 0, current[it] ?: 0)
    }
