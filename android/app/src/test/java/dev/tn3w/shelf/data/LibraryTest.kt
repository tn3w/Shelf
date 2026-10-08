package dev.tn3w.shelf.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration

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
}
