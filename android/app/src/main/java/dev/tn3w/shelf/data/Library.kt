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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
private const val BACKUP_ENTRY = "library.json"
private const val BOOKS_PREFIX = "books/"
private val BOOK_FILE = Regex("""-?\d+(\.\w+|-cover-\d+)""")

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
)

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
) {
    val coverHost
        get() = coverSource.ifEmpty { COVERS }.trimEnd('/')
}

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

data class Habit(val goal: Int, val today: Int, val streak: Int, val week: List<Int>) {
    val done
        get() = today >= goal
}

fun Book.toSaved(shelf: Shelf) =
    Saved(work, shelf, System.currentTimeMillis(), title, author, cover)

fun Saved.toBook() = bookOf(work, title, author, cover)

private fun bookOf(work: Int, title: String, author: String, cover: Int = 0) =
    Book(work, title, "", "", listOf(Author(0, author)), 0, cover, emptyList(), null)

val Book.isLocal
    get() = work < 0

private fun localWork(hash: Int) = -1 - (hash and Int.MAX_VALUE)

fun ownBook(title: String, author: String) =
    bookOf(localWork((title + author).hashCode()), title, author)

internal fun contentWork(input: InputStream): Int {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    generateSequence { input.read(buffer).takeIf { it >= 0 } }
        .forEach { digest.update(buffer, 0, it) }
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

class Library(private val context: Context) {
    private val store = context.dataStore

    private val data = store.data.catch {
        if (it !is IOException) throw it
        emit(emptyPreferences())
    }

    val saved = data.map { preferences ->
        preferences.decode(ENTRIES, emptyList<Saved>()).sortedByDescending {
            it.updated
        }
    }

    val recentSearches = data.map { it.decode(RECENT, emptyList<String>()) }

    val dismissed = data.map { it.decode(DISMISSED, emptySet<Int>()) }

    val progress = data.map { it.decode(PROGRESS, emptyMap<Int, Progress>()) }

    val settings = data.map { it.decode(SETTINGS, Settings()) }

    val habit = data.map {
        val goal = it.decode(SETTINGS, Settings()).dailyGoal
        habitOf(it.decode(ACTIVITY, emptyMap()), goal)
    }

    private fun habitOf(activity: Map<String, Int>, goal: Int): Habit {
        val today = LocalDate.now()
        fun pages(day: LocalDate) = activity[day.toString()] ?: 0
        val start = if (pages(today) >= goal) today else today.minusDays(1)
        val streak = generateSequence(start) { it.minusDays(1) }
            .takeWhile { pages(it) >= goal }
            .count()
        val week = (6 downTo 0).map { pages(today.minusDays(it.toLong())) }
        return Habit(goal, pages(today), streak, week)
    }

    private val booksDir
        get() = context.filesDir.resolve("books")

    fun bookFile(name: String) = booksDir.resolve(name)

    fun hasFile(progress: Progress?) =
        progress != null && bookFile(progress.file).exists()

    suspend fun importBook(book: Book, uri: Uri): Boolean {
        val name = copyBook(uri, context.displayName(uri), book.work) ?: return false
        val previous = progress.first()[book.work]?.takeIf { it.file == name }
        saveProgress(book.work, previous ?: Progress(name))
        place(book, Shelf.Reading)
        return true
    }

    suspend fun importFile(uri: Uri): Int? {
        val fileName = context.displayName(uri)
        val work = readFrom(uri, ::contentWork) ?: return null
        val name = copyBook(uri, fileName, work) ?: return null
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
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in READABLE_EXTENSIONS) return null
        val name = "$work.$extension"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        val copied = readFrom(uri) { input -> target.outputStream().use(input::copyTo) }
        if (copied != null) return name
        target.delete()
        return null
    }

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

    suspend fun deleteBook(work: Int) {
        progress.first()[work]?.let { bookFile(it.file).delete() }
        deleteCovers(work)
        update(PROGRESS, emptyMap<Int, Progress>()) { it - work }
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
            val rating = entries.firstOrNull { it.work == book.work }?.rating ?: 0
            val others = entries.filter { it.work != book.work }
            shelf?.let { others + book.toSaved(it).copy(rating = rating) } ?: others
        }

    suspend fun importCsv(
        uri: Uri,
        searcher: Searcher?,
        onProgress: (Float) -> Unit,
    ): CsvReport? {
        val text = readFrom(uri) { it.readBytes().decodeToString() } ?: return null
        val books = csvBooks(text).ifEmpty { return null }
        val missing = mutableListOf<String>()
        val entries = withContext(Dispatchers.Default) {
            books.mapIndexed { index, row ->
                onProgress(index.toFloat() / books.size)
                val match = searcher?.find(row.title, row.author)
                if (match == null) missing += row.title
                val book = match ?: ownBook(row.title, row.author)
                book.toSaved(row.shelf).copy(updated = row.date, rating = row.rating)
            }
        }
        update(ENTRIES, emptyList<Saved>()) { newestPerWork(it + entries) }
        return CsvReport(books.size, missing)
    }

    suspend fun exportCsv(uri: Uri): Boolean {
        val entries = saved.first()
        return writeTo(uri) { it.write(csvOf(entries).toByteArray()) }
    }

    suspend fun saveProgress(work: Int, progress: Progress) =
        update(PROGRESS, emptyMap<Int, Progress>()) { it + (work to progress) }

    suspend fun addPages(count: Int) =
        update(ACTIVITY, emptyMap<String, Int>()) { activity ->
            val today = LocalDate.now().toString()
            activity + (today to (activity[today] ?: 0) + count)
        }

    suspend fun updateSettings(change: (Settings) -> Settings) =
        update(SETTINGS, Settings(), change)

    suspend fun remember(query: String) = update(RECENT, emptyList<String>()) { recent ->
        val trimmed = query.trim()
        (listOf(trimmed) + recent.filter { it != trimmed }).take(RECENT_LIMIT)
    }

    suspend fun clearSearches() = store.edit { it.remove(RECENT) }

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

                entry.name.startsWith(BOOKS_PREFIX) -> extractBook(entry.name, archive)
            }
        }
        backup
    }

    private fun extractBook(entry: String, input: InputStream) {
        val name = entry.removePrefix(BOOKS_PREFIX)
        if (!BOOK_FILE.matches(name)) return
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        if (target.exists()) return
        target.outputStream().use { input.copyTo(it) }
    }

    private suspend fun merge(backup: Backup) = store.edit { it.merge(backup) }
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
    return imported.copy(
        onboarded = current.onboarded,
        catalogueSource = catalogue ?: current.catalogueSource,
        coverSource = covers ?: current.coverSource,
    )
}

private fun newestPerWork(entries: List<Saved>) =
    entries.groupBy { it.work }.map { (_, saved) -> saved.maxBy { it.updated } }

private fun maxPerDay(imported: Map<String, Int>, current: Map<String, Int>) =
    (imported.keys + current.keys).associateWith {
        maxOf(imported[it] ?: 0, current[it] ?: 0)
    }
