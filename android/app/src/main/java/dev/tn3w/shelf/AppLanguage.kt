package dev.tn3w.shelf

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

private const val RECORD =
    "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file"
private val RECORDED_TAGS = Regex("application_locales=\"([^\"]*)\"")

fun recordedLanguage(record: String?) =
    record?.let(RECORDED_TAGS::find)?.groupValues?.get(1).orEmpty()

fun languageRecord(tags: String) = "<locales application_locales=\"$tags\" />"

object AppLanguage {
    fun current(context: Context): Locale? =
        chosen(context).takeUnless { it.isEmpty }?.get(0)

    fun choose(activity: Activity, tags: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager(activity).applicationLocales = LocaleList.forLanguageTags(tags)
            return
        }
        record(activity).writeText(languageRecord(tags))
        activity.recreate()
    }

    fun localized(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val system = base.resources.configuration.locales
        val locales = chosen(base).takeUnless { it.isEmpty } ?: system
        LocaleList.setDefault(locales)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(locales)
        return base.createConfigurationContext(configuration)
    }

    private fun chosen(context: Context): LocaleList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager(context).applicationLocales
        }
        val record = runCatching { record(context).readText() }.getOrNull()
        return LocaleList.forLanguageTags(recordedLanguage(record))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun manager(context: Context) =
        context.getSystemService(LocaleManager::class.java)

    private fun record(context: Context) = context.filesDir.resolve(RECORD)
}
