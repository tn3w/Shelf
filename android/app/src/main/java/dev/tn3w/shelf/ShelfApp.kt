package dev.tn3w.shelf

import android.app.Application
import android.os.LocaleList
import dev.tn3w.shelf.data.Catalogue
import dev.tn3w.shelf.data.LANGUAGES
import dev.tn3w.shelf.data.Library
import dev.tn3w.shelf.data.Packs
import dev.tn3w.shelf.data.Recommender
import dev.tn3w.shelf.data.Searcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MONTH_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val AUTOMATIC_UPDATE_BYTES = 5L * 1024 * 1024

data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

class Loaded(val catalogue: Catalogue) {
    val searcher = Searcher(catalogue)
    val recommender = Recommender(catalogue)
}

sealed interface Download {
    data class Running(val progress: Float) : Download

    data object Failed : Download
}

class ShelfApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val library by lazy { Library(this) }
    val packs by lazy { Packs(this) }
    val loaded = MutableStateFlow<Loaded?>(null)
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val catalogueVersion = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val settings = library.settings.first()
            reload(settings.language.ifEmpty { systemLanguage() })
            if (settings.onboarded) checkCatalogue()
        }
    }

    fun systemLanguage(): String {
        val locales = LocaleList.getDefault()
        return (0 until locales.size())
            .map { locales[it].language }
            .firstOrNull { it in LANGUAGES } ?: "en"
    }

    fun reload(language: String) =
        scope.launch(Dispatchers.IO) {
            loaded.value = Loaded(packs.load(language))
            catalogueVersion.update { it + 1 }
        }

    fun download(language: String, pack: String) =
        scope.launch(Dispatchers.IO) {
            val key = "$language-$pack"
            if (downloads.value[key] is Download.Running) return@launch
            downloads.update { it + (key to Download.Running(0f)) }
            runCatching {
                packs.download(language, pack) { progress ->
                    downloads.update { it + (key to Download.Running(progress)) }
                }
            }
                .onSuccess {
                    downloads.update { it - key }
                    if (loaded.value?.catalogue?.language == language) reload(language)
                }
                .onFailure {
                    downloads.update { it + (key to Download.Failed) }
                }
        }

    fun remove(language: String, pack: String) =
        scope.launch(Dispatchers.IO) {
            packs.remove(language, pack)
            reload(language)
        }

    private suspend fun checkCatalogue() {
        val settings = library.settings.first()
        val now = System.currentTimeMillis()
        if (now - settings.lastCatalogueCheck < MONTH_MILLIS) return
        runCatching { packs.refreshManifest() }
            .onFailure {
                return
            }
        library.updateSettings { it.copy(lastCatalogueCheck = now) }
        val language = settings.language
        val updates = packs.pendingUpdates(language)
        val small = updates.sumOf { it.bytes } <= AUTOMATIC_UPDATE_BYTES
        if (small) updates.forEach { download(language, it.pack) }
    }
}
