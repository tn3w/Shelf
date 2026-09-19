package dev.tn3w.shelf

import android.app.Application
import android.os.LocaleList
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.tn3w.shelf.data.Catalogue
import dev.tn3w.shelf.data.LANGUAGES
import dev.tn3w.shelf.data.Library
import dev.tn3w.shelf.data.Packs
import dev.tn3w.shelf.data.Recommender
import dev.tn3w.shelf.data.Searcher
import dev.tn3w.shelf.data.USER_AGENT
import dev.tn3w.shelf.data.isOffline
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.OkHttpClient

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

class ShelfApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Random.nextLong()
    val library by lazy { Library(this) }
    val packs by lazy { Packs(this) }
    val loaded = MutableStateFlow<Loaded?>(null)
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val settings = library.settings.first()
            reload(settings.language.ifEmpty { systemLanguage() })
            if (settings.onboarded) checkCatalogue()
        }
    }

    // Coil type-checks the Application for this; dropping it restores its default client.
    override fun newImageLoader(context: PlatformContext) =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory({ anonymousClient })) }
            .build()

    private val anonymousClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor { chain ->
                val headers =
                    chain.request().newBuilder().header("User-Agent", USER_AGENT)
                chain.proceed(headers.header("Accept", "image/*").build())
            }
            .build()
    }

    fun systemLanguage(): String {
        val locales = LocaleList.getDefault()
        return (0 until locales.size())
            .map { locales[it].language }
            .firstOrNull { it in LANGUAGES } ?: "en"
    }

    fun reload(language: String) =
        scope.launch(Dispatchers.IO) { loaded.value = Loaded(packs.load(language)) }

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

    fun removeAll(language: String) =
        scope.launch(Dispatchers.IO) {
            packs.removeAll()
            reload(language)
        }

    private suspend fun checkCatalogue() {
        val settings = library.settings.first()
        if (!settings.catalogueUpdates || settings.isOffline(this)) return
        val now = System.currentTimeMillis()
        if (now - settings.lastCatalogueCheck < MONTH_MILLIS) return
        runCatching { packs.refreshManifest() }.getOrElse { return }
        library.updateSettings { it.copy(lastCatalogueCheck = now) }
        val language = settings.language
        val updates = packs.pendingUpdates(language)
        val small = updates.sumOf { it.bytes } <= AUTOMATIC_UPDATE_BYTES
        if (small) updates.forEach { download(language, it.pack) }
    }
}
