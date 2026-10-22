package dev.tn3w.shelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.serializer

open class ShelfViewModel(protected val app: ShelfApp) : ViewModel() {
    val library = app.library
    val loaded = app.loaded
    val sources = app.sources
    val downloads = app.downloads
    val session = app.session
    val recognizer
        get() = app.recognizer

    fun bookLanguage(settings: Settings) = app.bookLanguage(settings)

    fun updateSettings(change: (Settings) -> Settings) {
        detached { library.updateSettings(change) }
    }

    fun place(book: Book, shelf: Shelf?) {
        detached { library.place(book, shelf) }
    }

    suspend fun isbnsOf(book: Book, catalogue: Boolean): List<String> {
        val own = library.saved.first().firstOrNull {
            it.work == book.work
        }?.isbn.orEmpty()
        if (!catalogue) return isbnsFor(book, own, emptyList())
        val editions = withContext(Dispatchers.IO) {
            loaded.value?.catalogueOf(book.work)?.isbnsOf(book.work).orEmpty()
        }
        return isbnsFor(book, own, editions)
    }

    protected fun detached(block: suspend CoroutineScope.() -> Unit) =
        app.scope.launch(block = block)

    protected suspend fun <T> transfer(
        onProgress: (Float) -> Unit = {},
        work: suspend (onProgress: (Float) -> Unit) -> T,
    ): T = coroutineScope {
        val progress = MutableStateFlow(0f)
        val result = app.transfer { work { progress.value = it } }
        val watcher = launch { progress.collect(onProgress) }
        result.await().also { watcher.cancel() }
    }

    protected fun <T> Flow<T>.asState(): StateFlow<T?> = flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    protected fun <T> catalogueState(block: suspend Loaded.() -> T) =
        loaded.filterNotNull().map { it.block() }.asState()
}

@Composable
inline fun <reified T : ViewModel> shelfViewModel(
    key: String? = null,
    crossinline create: (ShelfApp) -> T,
): T = viewModel(key = key) { create(this[APPLICATION_KEY] as ShelfApp) }

@Composable
fun shelfViewModel() = shelfViewModel(create = ::ShelfViewModel)

inline fun <reified T> jsonSaver() = Saver<T, String>(
    save = { json.encodeToString(serializer<T>(), it) },
    restore = { json.decodeFromString(serializer<T>(), it) },
)
