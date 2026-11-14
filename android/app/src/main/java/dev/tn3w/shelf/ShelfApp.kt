package dev.tn3w.shelf

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.tn3w.shelf.cover.CoverFetcher
import dev.tn3w.shelf.cover.CoverKeyer
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val MONTH_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val AUTOMATIC_UPDATE_BYTES = 5L * 1024 * 1024
private const val SCAN_MODEL = "scan.bin"
private const val IMAGE_MEMORY = 0.15

class ShelfApp :
    Application(),
    SingletonImageLoader.Factory {
    private val logErrors = CoroutineExceptionHandler { _, error ->
        Log.e("ShelfApp", "Background task failed", error)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logErrors)
    val session = Random.nextLong()
    val library by lazy { Library(this, scope) }
    val narrator by lazy { Narrator(this) }
    val audiobooks by lazy { AudiobookPlayer(this) }
    val playback: Playback
        get() = audiobooks.takeIf { it.state.value.busy } ?: narrator
    val packs by lazy { Packs(this) }
    val models by lazy { Models(this) }
    val sources by lazy { Sources(this) }
    val catalogs by lazy { Catalogs(this) }
    private val scanCopy by lazy { filesDir.resolve(SCAN_MODEL) }
    val canScan get() = BuildConfig.BUNDLED_SCAN_MODEL || scanCopy.exists()
    val recognizer get() = Recognizer(scanModel())
    private val _loaded = MutableStateFlow<Loaded?>(null)
    val loaded = _loaded.asStateFlow()
    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads = _downloads.asStateFlow()
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val _openedWork = MutableStateFlow<Int?>(null)
    val openedWork = _openedWork.asStateFlow()
    private val _installConfirmation = MutableStateFlow<Intent?>(null)
    val installConfirmation = _installConfirmation.asStateFlow()
    private val _activeTransfers = MutableStateFlow(0)
    val activeTransfers = _activeTransfers.asStateFlow()
    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val backup = Mutex()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            library.settings.collect { networkAllowed = !it.isOffline(this@ShelfApp) }
        }
        scope.launch {
            val settings = library.settings.first()
            packs.source = settings.catalogueSource
            library.deleteTemporaryFiles()
            removeBundledCopies()
            showLanguage(bookLanguage(settings))
            if (settings.onboarded) checkCatalogue()
        }
    }

    // Coil type-checks the Application for this; dropping it restores its default client.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY).build()
        }
        .components {
            add(OkHttpNetworkFetcherFactory({ http }))
            add(CoverKeyer())
            add(CoverFetcher.Factory())
        }
        .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_UI_HIDDEN) return
        SingletonImageLoader.get(this).memoryCache?.clear()
        loaded.value?.trim()
    }

    private fun scanModel() = if (BuildConfig.BUNDLED_SCAN_MODEL) {
        assets.open(SCAN_MODEL).use { it.readBytes() }
    } else {
        scanCopy.readBytes()
    }

    fun keepBundled() {
        packs.copyBundled()
        if (!BuildConfig.BUNDLED_SCAN_MODEL) return
        replaceFile(scanCopy) { temporary ->
            assets.open(SCAN_MODEL).use { input ->
                temporary.outputStream().use(input::copyTo)
            }
        }
    }

    fun removeBundledCopies() {
        packs.removeBundledCopies()
        if (BuildConfig.BUNDLED_SCAN_MODEL) scanCopy.delete()
    }

    fun systemLanguage() = LocaleList.getDefault().getFirstMatch(LANGUAGES.toTypedArray())
        ?.language?.takeIf { it in LANGUAGES } ?: "en"

    fun bookLanguage(settings: Settings) = settings.language.ifEmpty { systemLanguage() }

    private var reloadJob: Job? = null
    private var requestedLanguage: String? = null

    @Synchronized
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
            _loaded.value = next
            library.redirect(next.catalogue::alias)
        }
    }

    fun backUp() = scope.launch(Dispatchers.IO) {
        backup.withLock { library.backUpToFolder() }
    }

    fun <T> transfer(work: suspend CoroutineScope.() -> T) = scope.async(Dispatchers.IO) {
        val first = _activeTransfers.getAndUpdate { it + 1 } == 0
        if (first) TransferService.start(this@ShelfApp)
        try {
            work()
        } finally {
            _activeTransfers.update { it - 1 }
        }
    }

    fun download(language: String, pack: String) {
        val key = "$language-$pack"
        if (!_downloads.claim(key)) return
        downloadJobs[key] = transfer {
            runSuspendCatching {
                packs.download(language, pack) { progress ->
                    _downloads.update { it + (key to Download.Running(progress)) }
                }
            }
                .onSuccess {
                    _downloads.update { it + (key to Download.Running(1f)) }
                    delay(DOWNLOAD_SETTLE_MILLIS)
                    _downloads.update { it - key }
                    if (loaded.value?.catalogue?.language == language) reload(language)
                }
                .onFailure { _downloads.update { it + (key to Download.Failed) } }
        }
    }

    fun downloadModel(model: Model) {
        val key = modelKey(model)
        if (!_downloads.claim(key)) return
        downloadJobs[key] = transfer {
            runSuspendCatching {
                models.download(model) { progress ->
                    _downloads.update { it + (key to Download.Running(progress)) }
                }
            }
                .onSuccess { _downloads.update { it - key } }
                .onFailure { error ->
                    _downloads.update { it + (key to Download.Failed) }
                    if (error is NoSpaceException) show(R.string.not_enough_space)
                }
        }
    }

    fun pauseModel(model: Model) = scope.launch(Dispatchers.IO) {
        val key = modelKey(model)
        downloadJobs.remove(key)?.cancelAndJoin()
        _downloads.update { it - key }
    }

    fun removeModel(model: Model) = scope.launch(Dispatchers.IO) {
        pauseModel(model).join()
        models.remove(model)
    }

    fun removePacks(language: String, pack: String? = null) =
        scope.launch(Dispatchers.IO) {
            val stopped = downloadJobs.keys.filter {
                it == "$language-$pack" || (pack == null && it.startsWith("$language-"))
            }
            stopped.forEach { downloadJobs.remove(it)?.cancelAndJoin() }
            _downloads.update { it - stopped.toSet() }
            packs.remove(language, pack)
            reload(language)
        }

    suspend fun changeSource(source: String) {
        val custom = if (source == REPOSITORY) "" else source
        library.updateSettings { it.copy(catalogueSource = custom) }
        packs.source = custom
        packs.forgetManifest()
    }

    fun openFile(uri: Uri) = scope.launch {
        val content = uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        open(content?.let { library.importFile(it, ::findInCatalogue) })
    }

    fun findInCatalogue(title: String, author: String) =
        loaded.value?.searcher?.find(title, author)

    fun open(work: Int?) {
        if (work != null) _openedWork.value = work else show(R.string.unsupported_file)
    }

    fun stopOthers(keep: Playback) = listOf(narrator, audiobooks)
        .filter { it !== keep && it.state.value.busy }
        .forEach(Playback::stop)

    fun consumeOpenedWork() {
        _openedWork.value = null
    }

    fun show(@StringRes message: Int) {
        _messages.trySend(message)
    }

    fun confirmInstall(intent: Intent?) {
        _installConfirmation.value = intent
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
        runSuspendCatching { packs.refreshManifest() }.getOrElse { return }
        library.updateSettings { it.copy(lastCatalogueCheck = now) }
        val language = bookLanguage(settings)
        val updates = packs.pendingUpdates(language)
        if (updates.sumOf { it.bytes } > AUTOMATIC_UPDATE_BYTES) return
        updates.forEach { download(language, it.pack) }
    }
}
