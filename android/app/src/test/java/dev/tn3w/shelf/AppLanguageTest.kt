package dev.tn3w.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun readsTheLanguageAppCompatRecorded() {
        val record = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
            "<locales application_locales=\"pt-PT\" />"

        assertEquals("pt-PT", recordedLanguage(record))
    }

    @Test
    fun readsBackItsOwnRecord() {
        assertEquals("de", recordedLanguage(languageRecord("de")))
        assertEquals("", recordedLanguage(languageRecord("")))
    }

    @Test
    fun missingRecordMeansSystemLanguage() {
        assertEquals("", recordedLanguage(null))
    }
}
