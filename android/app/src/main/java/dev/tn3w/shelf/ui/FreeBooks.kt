package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import java.net.URI

@Composable
fun FreeBookSettings(navigator: Navigator) {
    val (settings, update) = rememberSettings() ?: return
    val offline = settings.isOffline(LocalContext.current)
    val hint = "${stringResource(R.string.online_libraries_hint)} " +
        stringResource(R.string.free_books_note)
    SettingsGroup(hint = hint) {
        CatalogRows(navigator, settings, update, enabled = !offline)
        shelfViewModel().sources.bundled.forEach {
            ProviderSwitch(Provider(it), settings, update, enabled = !offline)
        }
        AddCatalogRow()
    }
    SettingsGroup(
        stringResource(R.string.free_books_own),
        stringResource(R.string.free_books_own_hint),
    ) { OwnSourceRows(settings, update, enabled = !offline) }
}

@Composable
fun ProviderSwitch(
    provider: Provider,
    settings: Settings,
    update: SettingsUpdate,
    enabled: Boolean,
    onOpen: (() -> Unit)? = null,
) {
    val on = provider.id in settings.providers
    fun toggle(value: Boolean) = update {
        val others = it.providers - provider.id
        it.copy(providers = if (value) others + provider.id else others)
    }
    val language = LocalConfiguration.current.locales[0].language
    val descriptions = provider.recipe.about
    val description = descriptions[language] ?: descriptions["en"]
        ?: stringResource(R.string.provider_own)
    val languages = provider.recipe.languages.map(::nativeName)
    val about = listOf(description) + languages + provider.site
    SettingRow(
        provider.name,
        about.filter { it.isNotEmpty() }.joinToString(" · "),
        enabled = enabled || onOpen != null,
        onClick = onOpen ?: { toggle(!on) },
    ) { Toggle(on, ::toggle, enabled) }
}

@Composable
fun FreeBookSheet(book: Book, viewModel: BookViewModel, onDismiss: () -> Unit) {
    val (settings, update) = rememberSettings() ?: return
    var choosing by rememberSaveable { mutableStateOf(settings.providers.isEmpty()) }
    val language = viewModel.bookLanguage(settings)
    val recipes = allRecipes().filter { Provider(it).serves(language) }
    val enabled = settings.enabledProviders(recipes).filter { it.serves(language) }
    val allCatalogs by viewModel.catalogs.all.collectAsStateWithLifecycle()
    val catalogs = allCatalogs.filter { it.serves(language) }
    val chosenCatalogs = catalogs.filter { it.id in settings.providers }
    val isbns = bookIsbns(book, enabled.any { it.usesIsbn })
    val query = Query(book.title, book.author, language, isbns.orEmpty())
    val offers by viewModel.offers.collectAsStateWithLifecycle()
    val downloading = offers.values.any { it is Download.Running }
    Sheet(
        onDismiss,
        stringResource(R.string.free_ebook),
        book.title,
        locked = downloading,
        padded = false,
        action = {
            if (choosing) return@Sheet
            val sources = stringResource(R.string.free_books_sources)
            Pill(sources, icon = Icons.Rounded.Tune, kind = PillKind.Bare) {
                choosing = true
            }
        },
    ) {
        if (!choosing) {
            if (isbns == null) return@Sheet LoadingBar()
            if (enabled.isEmpty() && chosenCatalogs.isEmpty()) {
                Note(R.string.free_books_no_source)
            }
            Offers(book, query, enabled, settings.onlineCovers, viewModel)
            val covers = settings.onlineCovers
            CatalogOffers(book, query, chosenCatalogs, covers, viewModel)
            Note(R.string.free_books_rights)
            return@Sheet
        }
        SettingsGroup(hint = stringResource(R.string.free_books_choose)) {
            catalogs.forEach { catalog -> CatalogSwitch(catalog, settings, update) }
            recipes.forEach {
                ProviderSwitch(Provider(it), settings, update, enabled = true)
            }
        }
        Pill(
            stringResource(R.string.search),
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            Icons.Rounded.Search,
            enabled = settings.providers.isNotEmpty(),
        ) { choosing = false }
    }
}

@Composable
private fun allRecipes(): List<Recipe> {
    val sources = shelfViewModel().sources
    val own by sources.own.collectAsStateWithLifecycle()
    return sources.bundled + own
}

@Composable
private fun bookIsbns(book: Book, catalogue: Boolean): List<String>? {
    val viewModel = shelfViewModel()
    return produceState<List<String>?>(null, book.work, catalogue) {
        value = viewModel.isbnsOf(book, catalogue)
    }.value
}

@Composable
private fun Offers(
    book: Book,
    query: Query,
    providers: List<Provider>,
    covers: Boolean,
    viewModel: BookViewModel,
) {
    val downloads by viewModel.offers.collectAsStateWithLifecycle()
    val results = remember { mutableStateMapOf<Provider, Result<List<Offer>>>() }
    val attempts = remember { mutableStateMapOf<Provider, Int>() }
    providers.forEach { provider ->
        key(provider) {
            LaunchedEffect(attempts[provider]) {
                results[provider] = runSuspendCatching { provider.search(query) }
            }
        }
    }
    fun retry(provider: Provider) {
        results.remove(provider)
        attempts[provider] = (attempts[provider] ?: 0) + 1
    }
    fun download(offer: Offer) = viewModel.download(book, offer)

    providers.forEach { provider ->
        SettingsGroup(provider.name) {
            ProviderResult(
                results[provider],
                downloads,
                covers,
                onRetry = { retry(provider) },
                onDownload = ::download,
            )
        }
    }
}

@Composable
private fun CatalogSwitch(catalog: Catalog, settings: Settings, update: SettingsUpdate) {
    val on = catalog.id in settings.providers
    fun toggle(value: Boolean) = update {
        val others = it.providers - catalog.id
        it.copy(providers = if (value) others + catalog.id else others)
    }
    SettingRow(catalog.name, catalog.host, onClick = { toggle(!on) }) {
        Toggle(on, ::toggle)
    }
}

@Composable
private fun CatalogOffers(
    book: Book,
    query: Query,
    catalogs: List<Catalog>,
    covers: Boolean,
    viewModel: BookViewModel,
) {
    val downloads by viewModel.offers.collectAsStateWithLifecycle()
    val results = remember { mutableStateMapOf<String, Result<List<Entry>>>() }
    val attempts = remember { mutableStateMapOf<String, Int>() }
    catalogs.forEach { catalog ->
        val client = viewModel.catalogs.client(catalog)
        LaunchedEffect(catalog.id, attempts[catalog.id]) {
            results[catalog.id] = runSuspendCatching { client.find(query) }
        }
        val result = results[catalog.id]
        val entries = result?.getOrNull()
        SettingsGroup(catalog.name) {
            when {
                result == null -> LoadingBar()

                entries == null -> RetryRow {
                    results.remove(catalog.id)
                    attempts[catalog.id] = (attempts[catalog.id] ?: 0) + 1
                }

                entries.isEmpty() -> SettingRow(stringResource(R.string.free_books_none))

                else -> entries.forEach { entry ->
                    val file = entry.files.first()
                    val cover = entry.cover?.takeIf { covers }
                    val host = cover?.let { URI(it).host }
                    DownloadRow(
                        entry.title,
                        listOf(entry.author, file.format.uppercase()) + size(file.bytes),
                        cover,
                        host?.let(client::authorization),
                        downloads[file],
                    ) { viewModel.download(book, catalog, file) }
                }
            }
        }
    }
}

@Composable
private fun size(bytes: Long) = listOfNotNull(bytes.takeIf { it > 0 }?.let { bytes(it) })

@Composable
private fun ProviderResult(
    result: Result<List<Offer>>?,
    downloads: Map<Any, Download>,
    covers: Boolean,
    onRetry: () -> Unit,
    onDownload: (Offer) -> Unit,
) {
    val offers = result?.getOrNull()
    when {
        result == null -> LoadingBar()
        offers == null -> RetryRow(onRetry)
        offers.isEmpty() -> SettingRow(stringResource(R.string.free_books_none))
        else -> offers.forEach { OfferRow(it, downloads[it], covers) { onDownload(it) } }
    }
}

@Composable
private fun RetryRow(onRetry: () -> Unit) =
    SettingRow(stringResource(R.string.free_books_unreachable), onClick = onRetry) {
        val retry = stringResource(R.string.free_books_retry)
        Icon(Icons.Rounded.Refresh, retry, tint = Look.colors.accent)
    }

@Composable
private fun OfferRow(
    offer: Offer,
    download: Download?,
    covers: Boolean,
    onClick: () -> Unit,
) = DownloadRow(
    offer.title,
    listOf(offer.author, offer.format.uppercase()) + size(offer.bytes),
    offer.cover.takeIf { covers },
    null,
    download,
    onClick,
)

@Composable
private fun DownloadRow(
    title: String,
    details: List<String>,
    cover: String?,
    authorization: String?,
    download: Download?,
    onClick: () -> Unit,
) {
    val failed = download is Download.Failed
    val icon = if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Download
    val action = if (failed) R.string.retry_item else R.string.download_item
    val summary = if (failed) {
        stringResource(R.string.free_books_failed)
    } else {
        details.filter { it.isNotBlank() }.joinToString(" · ")
    }
    MediaRow(title, summary, onClick, { RemoteCover(cover, authorization) }) {
        when (download) {
            is Download.Running -> DownloadProgress(download.progress)
            else -> Icon(icon, stringResource(action, title), tint = Look.colors.accent)
        }
    }
}

/** A row led by a cover or symbol: one found book, file or folder. */
@Composable
fun MediaRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = ScreenPadding, end = 10.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = Look.type.subhead,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    color = Look.colors.soft,
                    style = Look.type.note,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.Center) {
            trailing()
        }
    }
}

@Composable
fun RemoteCover(
    url: String?,
    authorization: String? = null,
    size: DpSize = DpSize(40.dp, 60.dp),
) = Box(
    Modifier.size(size).clip(Corners.cover).background(Look.colors.sunken),
    contentAlignment = Alignment.Center,
) {
    Icon(
        Icons.AutoMirrored.Rounded.MenuBook,
        null,
        Modifier.size(18.dp),
        Look.colors.faint,
    )
    url?.let {
        val request = ImageRequest.Builder(LocalPlatformContext.current).data(it)
        authorization?.let { value ->
            val headers = NetworkHeaders.Builder().set("Authorization", value)
            request.httpHeaders(headers.build())
        }
        val image = request.build()
        AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun DownloadProgress(value: Float) {
    val progress by animateFloatAsState(value)
    Ring(progress.takeIf { value > 0f })
}

@Composable
private fun Note(@StringRes text: Int) = Hint(
    stringResource(text),
    Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp),
)
