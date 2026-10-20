package dev.tn3w.shelf.ui

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.serializer

open class ShelfViewModel(application: Application) : AndroidViewModel(application) {
    protected val app = application as ShelfApp
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

    protected suspend fun open(work: Int?) {
        if (work != null) app.openedWork.value = work else app.showUnsupported()
    }

    protected fun detached(block: suspend CoroutineScope.() -> Unit) =
        app.scope.launch(block = block)

    protected suspend fun toast(@StringRes text: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun shelfViewModel(): ShelfViewModel = viewModel()

inline fun <reified T> jsonSaver() = Saver<T, String>(
    save = { json.encodeToString(serializer<T>(), it) },
    restore = { json.decodeFromString(serializer<T>(), it) },
)
