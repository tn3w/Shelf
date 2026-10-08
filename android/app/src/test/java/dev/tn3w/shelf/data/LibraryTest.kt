package dev.tn3w.shelf.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration

private inline fun <reified T> MutablePreferences.read(name: String) =
    json.decodeFromString<T>(this[stringPreferencesKey(name)]!!)

private fun backupOf(preferences: MutablePreferences) = with(preferences) {
    Backup(
        read("entries"),
        read("progress"),
        read("activity"),
        read("dismissed"),
        read("settings"),
    )
}

class LibraryTest {
    private val entries = stringPreferencesKey("entries")
    private val settings = stringPreferencesKey("settings")

    @Test
    fun updateKeepsUndecodableData() {
        val stored = """[{"work":1,"shelf":"Unknown","updated":0}]"""
        val preferences = mutablePreferencesOf(entries to stored)
        preferences.updateJson(entries, emptyList<Saved>()) {
            it + Saved(2, Shelf.Read, 0)
        }
        assertEquals(stored, preferences[entries])
    }

    @Test
    fun updateStartsFromFallbackWhenMissing() {
        val preferences = mutablePreferencesOf()
        preferences.updateJson(entries, emptyList<Saved>()) {
            it + Saved(2, Shelf.Read, 0)
        }
        assertEquals(
            listOf(Saved(2, Shelf.Read, 0)),
            json.decodeFromString<List<Saved>>(
                preferences[entries]!!,
            ),
        )
    }

    @Test
    fun mergeKeepsUndecodableData() {
        val stored = """[{"shelf":"Read"}]"""
        val preferences = mutablePreferencesOf(entries to stored)
        preferences.merge(Backup(entries = listOf(Saved(2, Shelf.Read, 0))))
        assertEquals(stored, preferences[entries])
    }

    @Test
    fun mergeDropsProgressOutsideBooks() {
        val progress = stringPreferencesKey("progress")
        val preferences = mutablePreferencesOf()
        val imported = mapOf(
            1 to Progress("../datastore/library"),
            2 to Progress("-2.epub"),
        )
        preferences.merge(Backup(progress = imported))
        val merged = json.decodeFromString<Map<Int, Progress>>(preferences[progress]!!)
        assertEquals(mapOf(2 to Progress("-2.epub")), merged)
    }

    @Test
    fun mergeKeepsSourcesWhenImportedInvalid() {
        val current = Settings(catalogueSource = "owner/repo", coverSource = "https://a")
        val preferences = mutablePreferencesOf(settings to json.encodeToString(current))
        val imported = Settings(catalogueSource = "http://evil", coverSource = "ftp://b")
        preferences.merge(Backup(settings = imported.copy(margin = 12)))
        val merged = json.decodeFromString<Settings>(preferences[settings]!!)
        assertEquals(current.copy(margin = 12), merged)
    }

    @Test
    fun unknownEnumFallsBackToDefault() {
        val decoded = json.decodeFromString<Settings>("""{"theme":"Sepia","margin":12}""")
        assertEquals(Settings(margin = 12), decoded)
    }

    @Test
    fun settingsSurviveUnknownValues() {
        val preferences =
            mutablePreferencesOf(settings to """{"theme":"Sepia","serif":false}""")
        preferences.updateJson(settings, Settings()) { it.copy(margin = 12) }
        val decoded = json.decodeFromString<Settings>(preferences[settings]!!)
        assertEquals(Settings(serif = false, margin = 12), decoded)
    }

    @Test
    fun habitTreatsNonPositiveGoalAsOne() {
        val today = LocalDate.of(2026, 1, 10)
        val activity = mapOf(today.toString() to 3, today.minusDays(1).toString() to 1)
        listOf(0, -5).forEach { goal ->
            val habit = habitOf(activity, goal, today)
            assertEquals(1, habit.goal)
            assertEquals(2, habit.streak)
        }
    }

    @Test
    fun datesEmitNewDayAfterMidnight() = runBlocking {
        val first = LocalDate.of(2026, 1, 10)
        val clock = listOf(first, first, first.plusDays(1)).iterator()
        val emitted = dates({ clock.next() }, Duration.ZERO).take(2).toList()
        assertEquals(listOf(first, first.plusDays(1)), emitted)
    }

    @Test
    fun habitCarriesItsDay() {
        val today = LocalDate.of(2026, 1, 10)
        val habit = habitOf(mapOf(today.minusDays(1).toString() to 5), 5, today)
        assertEquals(today, habit.day)
        assertEquals(listOf(0, 0, 0, 0, 0, 5, 0), habit.week)
    }

    @Test
    fun systemLanguageStoredAsAutomatic() {
        assertEquals("", storedLanguage("de", "de"))
        assertEquals("fr", storedLanguage("fr", "de"))
    }

    @Test
    fun mergeKeepsNewestEntryPerWork() {
        val current =
            listOf(Saved(1, Shelf.Read, 5, rating = 4), Saved(-7, Shelf.Want, 1))
        val preferences = mutablePreferencesOf(entries to json.encodeToString(current))
        val imported = listOf(
            Saved(1, Shelf.Want, 3),
            Saved(2, Shelf.Reading, 1),
            Saved(-7, Shelf.Read, 9, "Own", "Me"),
        )
        preferences.merge(Backup(entries = imported))
        val merged = json.decodeFromString<List<Saved>>(preferences[entries]!!)
        assertEquals(
            setOf(current[0], imported[1], imported[2]),
            merged.toSet(),
        )
    }

    @Test
    fun mergeCombinesActivityProgressAndDismissed() {
        val current = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 40)),
            activity = mapOf("2026-01-01" to 5, "2026-01-02" to 9),
            dismissed = setOf(3),
        )
        val onboarded = json.encodeToString(Settings(onboarded = true))
        val preferences = mutablePreferencesOf(settings to onboarded)
        preferences.merge(current)
        val imported = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 2), 2 to Progress("2.pdf")),
            activity = mapOf("2026-01-01" to 7, "2026-01-03" to 1),
            dismissed = setOf(4),
            settings = Settings(dailyGoal = 30),
        )
        preferences.merge(imported)
        val expected = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 40), 2 to Progress("2.pdf")),
            activity = mapOf("2026-01-01" to 7, "2026-01-02" to 9, "2026-01-03" to 1),
            dismissed = setOf(3, 4),
            settings = Settings(onboarded = true, dailyGoal = 30),
        )
        assertEquals(expected, backupOf(preferences))
    }

    @Test
    fun streakSurvivesUntilTodayIsOver() {
        val today = LocalDate.of(2026, 3, 29)
        fun day(back: Long) = today.minusDays(back).toString()
        val activity = mapOf(day(1) to 10, day(2) to 12, day(3) to 2, day(4) to 10)
        assertEquals(
            2 to false,
            habitOf(activity, 10, today).let {
                it.streak to it.done
            },
        )
        val met = activity + (today.toString() to 10)
        assertEquals(3 to true, habitOf(met, 10, today).let { it.streak to it.done })
        assertEquals(0, habitOf(activity, 10, today.plusDays(1)).streak)
    }
}
