package dev.tn3w.shelf

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.random.Random

private const val MONTH_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val AUTOMATIC_UPDATE_BYTES = 5L * 1024 * 1024

class ShelfApp :
    Application(),
    SingletonImageLoader.Factory {
    private val logErrors = CoroutineExceptionHandler { _, error ->
        Log.e("ShelfApp", "Background task failed", error)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logErrors)
    val session = Random.nextLong()
    val library by lazy { Library(this, scope) }
    val packs by lazy { Packs(this) }
    val sources by lazy { Sources(this) }
    val recognizer by lazy { assets.open("scan.bin").use { Recognizer(it.readBytes()) } }
    val loaded = MutableStateFlow<Loaded?>(null)
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val settings = library.settings.first()
            packs.source = settings.catalogueSource
            library.deleteTemporaryFiles()
            showLanguage(bookLanguage(settings))
            if (settings.onboarded) checkCatalogue()
        }
    }

    // Coil type-checks the Application for this; dropping it restores its default client.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory({ http })) }
        .build()

    fun systemLanguage() = LocaleList.getDefault().getFirstMatch(LANGUAGES.toTypedArray())
        ?.language?.takeIf { it in LANGUAGES } ?: "en"

    fun bookLanguage(settings: Settings) = settings.language.ifEmpty { systemLanguage() }

    private var reloadJob: Job? = null
    private var requestedLanguage: String? = null

    fun showLanguage(language: String) {
        if (language != requestedLanguage) reload(language)
    }

    @Synchronized
    fun reload(language: String) {
        requestedLanguage = language
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.IO) {
            val next = Loaded(packs.load(language)) {
                LANGUAGES.filter { it != language }
                    .map(packs::load)
                    .filter { it.segments.isNotEmpty() }
            }
            next.catalogue.warmUp()
            ensureActive()
            loaded.value = next
            library.redirect(next.catalogue::alias)
        }
    }

    fun download(language: String, pack: String) = scope.launch(Dispatchers.IO) {
        val key = "$language-$pack"
        if (!downloads.claim(key)) return@launch
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

    fun removePacks(language: String, pack: String? = null, everywhere: Boolean = false) =
        scope.launch(Dispatchers.IO) {
            packs.remove(language.takeUnless { everywhere }, pack)
            reload(language)
        }

    suspend fun changeSource(source: String) {
        val custom = if (source == REPOSITORY) "" else source
        library.updateSettings { it.copy(catalogueSource = custom) }
        packs.source = custom
        packs.forgetManifest()
    }

    suspend fun importCatalogue(language: String, uris: List<Uri>): Boolean {
        val imported = uris.count { uri ->
            readFrom(uri) { packs.importFile(displayName(uri), it) } == true
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
        if (updates.sumOf { it.bytes } > AUTOMATIC_UPDATE_BYTES) return
        updates.forEach { download(language, it.pack) }
    }
}
