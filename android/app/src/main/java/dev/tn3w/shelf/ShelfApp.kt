package dev.tn3w.shelf

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import kotlin.random.Random

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

class ShelfApp :
    Application(),
    SingletonImageLoader.Factory {
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
            packs.source = settings.catalogueSource
            reload(bookLanguage(settings))
            if (settings.onboarded) checkCatalogue()
        }
    }

    // Coil type-checks the Application for this; dropping it restores its default client.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
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

    fun bookLanguage(settings: Settings) = settings.language.ifEmpty { systemLanguage() }

    private var reloadJob: Job? = null

    @Synchronized
    fun reload(language: String) {
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.IO) {
            val next = Loaded(packs.load(language))
            ensureActive()
            loaded.value = next
        }
    }

    fun download(language: String, pack: String) = scope.launch(Dispatchers.IO) {
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
            .onFailure { downloads.update { it + (key to Download.Failed) } }
    }

    fun remove(language: String, pack: String) = scope.launch(Dispatchers.IO) {
        packs.remove(language, pack)
        reload(language)
    }

    fun removeAll(language: String) = scope.launch(Dispatchers.IO) {
        packs.removeAll()
        reload(language)
    }

    suspend fun changeSource(source: String) {
        val custom = if (source == REPOSITORY) "" else source
        library.updateSettings { it.copy(catalogueSource = custom) }
        packs.source = custom
        packs.forgetManifest()
    }

    suspend fun importCatalogue(language: String, uris: List<Uri>): Boolean {
        val imported = withContext(Dispatchers.IO) {
            uris.count { uri ->
                runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        packs.importFile(displayName(uri), it)
                    } == true
                }
                    .getOrDefault(false)
            }
        }
        reload(language)
        return imported == uris.size
    }

    private suspend fun checkCatalogue() {
        val settings = library.settings.first()
        if (!settings.catalogueUpdates || settings.isOffline(this)) return
        val now = System.currentTimeMillis()
        if (now - settings.lastCatalogueCheck < MONTH_MILLIS) return
        runCatching { packs.refreshManifest() }.getOrElse { return }
        library.updateSettings { it.copy(lastCatalogueCheck = now) }
        val language = bookLanguage(settings)
        val updates = packs.pendingUpdates(language)
        val small = updates.sumOf { it.bytes } <= AUTOMATIC_UPDATE_BYTES
        if (small) updates.forEach { download(language, it.pack) }
    }
}
