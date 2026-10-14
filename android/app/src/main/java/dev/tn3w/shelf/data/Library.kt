package dev.tn3w.shelf.data

import android.Manifest.permission.INTERNET
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
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
private val ENTRIES = stringPreferencesKey("entries")
private val RECENT = stringPreferencesKey("recent")
private val PROGRESS = stringPreferencesKey("progress")
private val ACTIVITY = stringPreferencesKey("activity")
private val SETTINGS = stringPreferencesKey("settings")
private val DISMISSED = stringPreferencesKey("dismissed")
private val PDFS_NORMALISED = booleanPreferencesKey("pdfsNormalised")
private const val BACKUP_ENTRY = "library.json"
private const val BOOKS_PREFIX = "books/"
private val BOOK_FILE = Regex("""-?\d+(\.\w+|-cover-\d+)""")
val FONT_SCALES = 0.7f..1.8f

enum class Shelf {
    Reading,
    Want,
    Read,
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

enum class ThemeMode {
    System,
    Light,
    Dark,
}

enum class PageColor {
    Theme,
    Paper,
    Night,
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
    val catalogueSource: String = "",
    val coverSource: String = "",
    val dnbCoverSource: String = "",
    val providers: Set<String> = emptySet(),
    val librarySort: LibrarySort = LibrarySort.Added,
    val libraryList: Boolean = false,
) {
    val coverHost
        get() = coverSource.ifEmpty { COVERS }.trimEnd('/')

    val dnbCoverHost
        get() = dnbCoverSource.ifEmpty { DNB_COVERS }.trimEnd('/')
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
    "",
    "",
    listOf(Author(0, author)),
    0,
    cover,
    emptyList(),
    null,
    libraryCover,
)

val Book.isLocal
    get() = work < 0

private fun localWork(hash: Int) = -1 - (hash and Int.MAX_VALUE)

fun ownBook(title: String, author: String) =
    bookOf(localWork((title + author).hashCode()), title, author)

private const val IMPORT_TEMPORARY = "import.tmp"

internal fun contentWork(input: InputStream, output: OutputStream? = null): Int {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach {
        digest.update(buffer, 0, it)
        output?.write(buffer, 0, it)
    }
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

private inline fun <reified T> decodeOrNull(text: String) =
    runCatching { json.decodeFromString<T>(text) }.getOrNull()

private inline fun <reified T> Preferences.decode(
    key: Preferences.Key<String>,
    fallback: T,
): T = this[key]?.let { decodeOrNull<T>(it) } ?: fallback

internal inline fun <reified T> MutablePreferences.updateJson(
    key: Preferences.Key<String>,
    fallback: T,
    change: (T) -> T,
) {
    val stored = this[key]
    val current = if (stored == null) fallback else decodeOrNull<T>(stored) ?: return
    this[key] = json.encodeToString(change(current))
}

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

    val saved = data.map { preferences ->
        preferences.decode(ENTRIES, emptyList<Saved>()).sortedByDescending {
            it.updated
        }
    }.shared()

    val recentSearches = data.map { it.decode(RECENT, emptyList<String>()) }.shared()

    val dismissed = data.map { it.decode(DISMISSED, emptySet<Int>()) }.shared()

    val progress = data.map { it.decode(PROGRESS, emptyMap<Int, Progress>()) }.shared()

    val settings = data.map { it.decode(SETTINGS, Settings()) }.shared()

    val habit = combine(data, dates()) { preferences, today ->
        val goal = preferences.decode(SETTINGS, Settings()).dailyGoal
        habitOf(preferences.decode(ACTIVITY, emptyMap()), goal, today)
    }.shared()

    private val booksDir
        get() = context.filesDir.resolve("books")

    fun bookFile(name: String) = booksDir.resolve(name)

    suspend fun normaliseStoredPdfs() {
        if (data.first()[PDFS_NORMALISED] == true) return
        withContext(Dispatchers.IO) {
            booksDir.listFiles { file -> file.extension == "pdf" }
                .orEmpty()
                .forEach(::replaceJpegIccProfiles)
        }
        store.edit { it[PDFS_NORMALISED] = true }
    }

    suspend fun hasFile(progress: Progress?) = withContext(Dispatchers.IO) {
        progress != null && bookFile(progress.file).exists()
    }

    suspend fun importBook(book: Book, uri: Uri): Boolean {
        val fileName = withContext(Dispatchers.IO) { context.displayName(uri) }
        val name = copyBook(uri, fileName, book.work) ?: return false
        startReading(book, name)
        return true
    }

    suspend fun download(book: Book, offer: Offer, onProgress: (Float) -> Unit): Boolean {
        val name = "${book.work}.${offer.format}"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (!fetch(offer, target, onProgress)) return false
        finishBook(target, book.work)
        startReading(book, name)
        return true
    }

    private suspend fun fetch(offer: Offer, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val report = { progress: Float ->
                ensureActive()
                onProgress(progress)
            }
            runCatching { replaceFile(target) { fetchBook(offer, it, report) } }.isSuccess
        }

    private suspend fun startReading(book: Book, name: String) {
        val previous = progress.first()[book.work]?.takeIf { it.file == name }
        saveProgress(book.work, previous ?: Progress(name))
        place(book, Shelf.Reading)
    }

    suspend fun importFile(uri: Uri): Int? {
        val fileName = withContext(Dispatchers.IO) { context.displayName(uri) }
        if (!isReadable(fileName)) return null
        val temporary = bookFile(IMPORT_TEMPORARY).apply { parentFile?.mkdirs() }
        val work = readFrom(uri) { input ->
            temporary.outputStream().use { contentWork(input, it) }
        }
        if (work == null) {
            temporary.delete()
            return null
        }
        val name = "$work.${extensionOf(fileName)}"
        temporary.renameTo(bookFile(name))
        finishBook(bookFile(name), work)
        val metadata = withContext(Dispatchers.IO) { readMetadata(bookFile(name)) }
        val title = metadata?.title ?: fileName.substringBeforeLast('.').replace('_', ' ')
        update(PROGRESS, emptyMap<Int, Progress>()) {
            if (work in it) it else it + (work to Progress(name))
        }
        val existing = saved.first().firstOrNull { it.work == work }
        val book = bookOf(work, title, metadata?.author.orEmpty(), existing?.cover ?: 0)
        place(book, existing?.shelf ?: Shelf.Reading)
        return work
    }

    private suspend fun copyBook(uri: Uri, fileName: String, work: Int): String? {
        if (!isReadable(fileName)) return null
        val name = "$work.${extensionOf(fileName)}"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        val copied = readFrom(uri) { input -> target.outputStream().use(input::copyTo) }
        if (copied == null) {
            target.delete()
            return null
        }
        finishBook(target, work)
        return name
    }

    private fun finishBook(file: File, work: Int) {
        if (file.extension == "pdf") replaceJpegIccProfiles(file)
        deleteOtherFormats(booksDir, work, keep = file.name)
    }

    private fun extensionOf(fileName: String) =
        fileName.substringAfterLast('.', "").lowercase()

    private fun isReadable(fileName: String) =
        extensionOf(fileName) in READABLE_EXTENSIONS

    fun coverFile(book: Book) = bookFile("${book.work}-cover-${book.cover}")

    suspend fun saveCover(work: Int, uri: Uri): Int? {
        val cover = (System.currentTimeMillis() / 1000).toInt()
        val name = "$work-cover-$cover"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (readFrom(uri) { input -> target.outputStream().use(input::copyTo) } == null) {
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

    private suspend fun <T> readFrom(uri: Uri, read: (InputStream) -> T): T? =
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use(read) }
                .getOrNull()
        }

    private suspend fun writeTo(uri: Uri, write: (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use(write) != null
            }
                .getOrDefault(false)
        }

    suspend fun deleteBook(work: Int, keepShelves: Boolean = false) {
        progress.first()[work]?.let { bookFile(it.file).delete() }
        update(PROGRESS, emptyMap<Int, Progress>()) { it - work }
        if (keepShelves) return
        deleteCovers(work)
        update(ENTRIES, emptyList<Saved>()) { entries ->
            entries.filter { it.work != work }
        }
    }

    private suspend inline fun <reified T> update(
        key: Preferences.Key<String>,
        fallback: T,
        crossinline change: (T) -> T,
    ) = store.edit { it.updateJson(key, fallback, change) }

    suspend fun place(book: Book, shelf: Shelf?) =
        update(ENTRIES, emptyList<Saved>()) { entries ->
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
        val text = readFrom(uri) { it.readBytes().decodeToString() } ?: return null
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
        update(ENTRIES, emptyList<Saved>()) { newestPerWork(it + entries) }
        return CsvReport(books.size, missing)
    }

    suspend fun exportCsv(uri: Uri): Boolean {
        val entries = saved.first()
        return writeTo(uri) { it.write(csvOf(entries).toByteArray()) }
    }

    suspend fun saveProgress(work: Int, progress: Progress, pagesRead: Int = 0) =
        store.edit {
            it.updateJson(PROGRESS, emptyMap<Int, Progress>()) { all ->
                all + (work to progress)
            }
            if (pagesRead > 0) {
                it.updateJson(ACTIVITY, emptyMap<String, Int>()) { activity ->
                    val today = LocalDate.now().toString()
                    activity + (today to (activity[today] ?: 0) + pagesRead)
                }
            }
        }

    suspend fun updateSettings(change: (Settings) -> Settings) =
        update(SETTINGS, Settings(), change)

    suspend fun remember(query: String) = update(RECENT, emptyList<String>()) { recent ->
        val trimmed = query.trim()
        (listOf(trimmed) + recent.filter { it != trimmed }).take(RECENT_LIMIT)
    }

    suspend fun clearSearches() = store.edit { it.remove(RECENT) }

    suspend fun redirect(alias: (Int) -> Int?) {
        val works =
            saved.first().map { it.work } + progress.first().keys + dismissed.first()
        if (works.none { alias(it) != null }) return
        store.edit { it.redirect(alias) }
    }

    suspend fun dismiss(work: Int) = update(DISMISSED, emptySet<Int>()) { it + work }

    suspend fun exportTo(uri: Uri): Boolean {
        val backup = data.first().toBackup()
        return writeTo(uri) { writeArchive(it, backup) }
    }

    private fun writeArchive(output: OutputStream, backup: Backup) =
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry(BACKUP_ENTRY))
            archive.write(json.encodeToString(backup).toByteArray())
            archive.closeEntry()
            booksDir.listFiles().orEmpty().forEach { file ->
                archive.putNextEntry(ZipEntry("$BOOKS_PREFIX${file.name}"))
                file.inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }

    suspend fun importFrom(uri: Uri): Boolean {
        val backup = readFrom(uri, ::readArchive) ?: return false
        merge(backup)
        return true
    }

    private fun readArchive(input: InputStream) = ZipInputStream(input).use { archive ->
        var backup: Backup? = null
        generateSequence { archive.nextEntry }.forEach { entry ->
            when {
                entry.name == BACKUP_ENTRY ->
                    backup = json.decodeFromString(archive.readBytes().decodeToString())

                entry.name.startsWith(BOOKS_PREFIX) ->
                    extractBook(booksDir, entry.name, archive)
            }
        }
        backup
    }

    private suspend fun merge(backup: Backup) = store.edit { it.merge(backup) }
}

internal fun extractBook(directory: File, entry: String, input: InputStream) {
    val name = entry.removePrefix(BOOKS_PREFIX)
    if (!BOOK_FILE.matches(name)) return
    val target = directory.resolve(name).apply { parentFile?.mkdirs() }
    if (target.exists()) return
    replaceFile(target) { file ->
        file.outputStream().use(input::copyTo)
        if (name.endsWith(".pdf")) replaceJpegIccProfiles(file)
    }
}

private fun Preferences.toBackup() = Backup(
    decode(ENTRIES, emptyList()),
    decode(PROGRESS, emptyMap()),
    decode(ACTIVITY, emptyMap()),
    decode(DISMISSED, emptySet()),
    decode(SETTINGS, Settings()),
)

internal fun MutablePreferences.merge(imported: Backup) {
    updateJson(ENTRIES, emptyList<Saved>()) { newestPerWork(it + imported.entries) }
    val progress = imported.progress.filterValues { BOOK_FILE.matches(it.file) }
    updateJson(PROGRESS, emptyMap<Int, Progress>()) { progress + it }
    updateJson(ACTIVITY, emptyMap<String, Int>()) { maxPerDay(imported.activity, it) }
    updateJson(DISMISSED, emptySet<Int>()) { it + imported.dismissed }
    updateJson(SETTINGS, Settings()) { restoredSettings(imported.settings, it) }
}

private fun restoredSettings(imported: Settings, current: Settings): Settings {
    val catalogue = imported.catalogueSource.takeIf(::isValidSource)
    val covers = imported.coverSource.takeIf(::isValidCoverSource)
    val dnbCovers = imported.dnbCoverSource.takeIf(::isValidCoverSource)
    return imported.copy(
        onboarded = current.onboarded,
        dailyGoal = imported.dailyGoal.coerceIn(0, MAX_DAILY_GOAL),
        language = imported.language.takeIf { it.isEmpty() || it in LANGUAGES }
            ?: current.language,
        fontScale = imported.fontScale.coerceIn(FONT_SCALES),
        lineSpacing = imported.lineSpacing.coerceAtLeast(1f),
        margin = imported.margin.coerceAtLeast(0),
        catalogueSource = catalogue ?: current.catalogueSource,
        coverSource = covers ?: current.coverSource,
        dnbCoverSource = dnbCovers ?: current.dnbCoverSource,
    )
}

internal fun MutablePreferences.redirect(alias: (Int) -> Int?) {
    fun moved(work: Int) = alias(work) ?: work
    updateJson(ENTRIES, emptyList<Saved>()) { entries ->
        newestPerWork(entries.map { it.copy(work = moved(it.work)) })
    }
    updateJson(PROGRESS, emptyMap<Int, Progress>()) { all ->
        all.mapKeys { moved(it.key) }
    }
    updateJson(DISMISSED, emptySet<Int>()) { dismissed ->
        dismissed.mapTo(HashSet(), ::moved)
    }
}

private fun newestPerWork(entries: List<Saved>) =
    entries.groupBy { it.work }.map { (_, saved) -> saved.maxBy { it.updated } }

private fun maxPerDay(imported: Map<String, Int>, current: Map<String, Int>) =
    (imported.keys + current.keys).associateWith {
        maxOf(imported[it] ?: 0, current[it] ?: 0)
    }
