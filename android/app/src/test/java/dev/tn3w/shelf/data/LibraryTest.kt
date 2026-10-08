package dev.tn3w.shelf.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
