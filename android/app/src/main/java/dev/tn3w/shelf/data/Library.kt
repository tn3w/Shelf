package dev.tn3w.shelf.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RECENT_LIMIT = 8
private val Context.dataStore by preferencesDataStore("library")
private val ENTRIES = stringPreferencesKey("entries")
private val RECENT = stringPreferencesKey("recent")
private val PROGRESS = stringPreferencesKey("progress")
private val ACTIVITY = stringPreferencesKey("activity")
private val SETTINGS = stringPreferencesKey("settings")
private val DISMISSED = stringPreferencesKey("dismissed")
private val json = Json { ignoreUnknownKeys = true }

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

@Serializable
enum class ThemeMode {
    System,
    Light,
    Dark,
}

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val language: String = "",
    val theme: ThemeMode = ThemeMode.System,
    val dailyGoal: Int = 10,
    val fontScale: Float = 1f,
    val onlineCovers: Boolean = true,
    val checkUpdates: Boolean = true,
    val lastCatalogueCheck: Long = 0,
    val lastAppCheck: Long = 0,
)

data class Habit(val goal: Int, val today: Int, val streak: Int, val week: List<Int>) {
    val done
        get() = today >= goal
}

fun Book.toSaved(shelf: Shelf) =
    Saved(work, shelf, System.currentTimeMillis(), title, author, cover)

fun Saved.toBook() =
    Book(
        work,
        title,
        "",
        "",
        listOf(Author(0, author)),
        0,
        cover,
        emptyList(),
        null,
    )

private inline fun <reified T> Preferences.decode(
    key: Preferences.Key<String>,
    fallback: T,
): T =
    this[key]?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
        ?: fallback

class Library(private val context: Context) {
    private val store = context.dataStore

    val saved =
        store.data.map { preferences ->
            preferences.decode(ENTRIES, emptyList<Saved>()).sortedByDescending {
                it.updated
            }
        }

    val recentSearches = store.data.map { it.decode(RECENT, emptyList<String>()) }

    val dismissed = store.data.map { it.decode(DISMISSED, emptySet<Int>()) }

    val progress = store.data.map { it.decode(PROGRESS, emptyMap<Int, Progress>()) }

    val settings = store.data.map { it.decode(SETTINGS, Settings()) }

    val habit =
        store.data.map { preferences ->
            val activity = preferences.decode(ACTIVITY, emptyMap<String, Int>())
            habitOf(
                activity,
                preferences.decode(SETTINGS, Settings()).dailyGoal,
                LocalDate.now(),
            )
        }

    private fun habitOf(activity: Map<String, Int>, goal: Int, today: LocalDate): Habit {
        fun pages(day: LocalDate) = activity[day.toString()] ?: 0
        val start = if (pages(today) >= goal) today else today.minusDays(1)
        val streak =
            generateSequence(start) { it.minusDays(1) }
                .takeWhile { pages(it) >= goal }
                .count()
        val week = (6 downTo 0).map { pages(today.minusDays(it.toLong())) }
        return Habit(goal, pages(today), streak, week)
    }

    fun bookFile(name: String) = context.filesDir.resolve("books").resolve(name)

    suspend fun importBook(book: Book, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val displayName =
            resolver
                .query(uri, null, null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column)
                    else null
                }
                .orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in READABLE_EXTENSIONS) return false
        val name = "${book.work}.$extension"
        val target = bookFile(name).apply { parentFile?.mkdirs() }
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        saveProgress(book.work, Progress(name))
        place(book, Shelf.Reading)
        return true
    }

    private suspend inline fun <reified T> update(
        key: Preferences.Key<String>,
        fallback: T,
        crossinline change: (T) -> T,
    ) = store.edit { it[key] = json.encodeToString(change(it.decode(key, fallback))) }

    suspend fun place(book: Book, shelf: Shelf?) =
        update(ENTRIES, emptyList<Saved>()) { entries ->
            val others = entries.filter { it.work != book.work }
            shelf?.let { others + book.toSaved(it) } ?: others
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

    suspend fun remember(query: String) =
        update(RECENT, emptyList<String>()) { recent ->
            val trimmed = query.trim()
            (listOf(trimmed) + recent.filter { it != trimmed }).take(RECENT_LIMIT)
        }

    suspend fun clearSearches() = store.edit { it.remove(RECENT) }

    suspend fun dismiss(work: Int) = update(DISMISSED, emptySet<Int>()) { it + work }
}
