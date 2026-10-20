package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

@Composable
fun FreeBookSettings() {
    val (settings, update) = rememberSettings() ?: return
    val offline = settings.isOffline(LocalContext.current)
    SettingsGroup(hint = stringResource(R.string.free_books_note)) {
        shelfViewModel().sources.bundled.forEach {
            ProviderSwitch(Provider(it), settings, update, enabled = !offline)
        }
    }
    SettingsGroup(
        stringResource(R.string.free_books_own),
        stringResource(R.string.free_books_own_hint),
    ) { OwnSourceRows(settings, update, enabled = !offline) }
    SettingsGroup(
        stringResource(R.string.instant_source),
        stringResource(R.string.instant_source_hint),
    ) { InstantSourceRows(settings, update, enabled = !offline) }
}

@Composable
private fun InstantSourceRows(
    settings: Settings,
    update: SettingsUpdate,
    enabled: Boolean,
) {
    val language = shelfViewModel().bookLanguage(settings)
    val recipes = allRecipes().filter { Provider(it).serves(language) }
    val selected = settings.instantProvider(recipes, language)?.id.orEmpty()
    val options = listOf("" to stringResource(R.string.instant_source_off)) +
        recipes.map { it.id to it.name }
    options.forEach { (id, name) ->
        fun select() = update { it.copy(instantSource = id) }
        SettingRow(name, enabled = enabled, onClick = ::select) {
            RadioButton(selected = id == selected, onClick = ::select, enabled = enabled)
        }
    }
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
    ) { Switch(checked = on, onCheckedChange = ::toggle, enabled = enabled) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeBookSheet(book: Book, onDismiss: () -> Unit) {
    val viewModel: BookViewModel = viewModel()
    val (settings, update) = rememberSettings() ?: return
    var choosing by rememberSaveable { mutableStateOf(settings.providers.isEmpty()) }
    val language = viewModel.bookLanguage(settings)
    val recipes = allRecipes().filter { Provider(it).serves(language) }
    val enabled = settings.enabledProviders(recipes).filter { it.serves(language) }
    val isbns = bookIsbns(book, enabled.any { it.usesIsbn })
    val query = Query(book.title, book.author, language, isbns.orEmpty())
    val offers by viewModel.offers.collectAsStateWithLifecycle()
    val downloading by rememberUpdatedState(offers.values.any { it is Download.Running })
    val sheet = rememberModalBottomSheetState(true) { it != Hidden || !downloading }
    ModalBottomSheet({ if (!downloading) onDismiss() }, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle(book) { if (!choosing) SourcesButton { choosing = true } }
            if (!choosing) {
                if (isbns == null) {
                    LoadingBar()
                } else {
                    Offers(book, query, enabled, settings.onlineCovers)
                }
                return@Column
            }
            SettingsGroup(hint = stringResource(R.string.free_books_choose)) {
                recipes.forEach {
                    ProviderSwitch(Provider(it), settings, update, enabled = true)
                }
            }
            SearchButton(settings.providers.isNotEmpty()) { choosing = false }
        }
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
private fun SheetTitle(book: Book, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.free_ebook),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        action()
    }
}

@Composable
private fun SourcesButton(onClick: () -> Unit) = TextButton(onClick = onClick) {
    IconLabel(Icons.Outlined.Tune, R.string.free_books_sources)
}

@Composable
private fun SearchButton(enabled: Boolean, onClick: () -> Unit) = Button(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth().padding(ScreenPadding, 16.dp),
) {
    IconLabel(Icons.Outlined.Search, R.string.search)
}

@Composable
private fun Offers(
    book: Book,
    query: Query,
    providers: List<Provider>,
    covers: Boolean,
) {
    val viewModel: BookViewModel = viewModel()
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

    if (providers.isEmpty()) Note(R.string.free_books_no_source)
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
    Note(R.string.free_books_rights)
}

@Composable
private fun ProviderResult(
    result: Result<List<Offer>>?,
    downloads: Map<Offer, Download>,
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
        Icon(Icons.Outlined.Refresh, stringResource(R.string.free_books_retry))
    }

@Composable
private fun OfferRow(
    offer: Offer,
    download: Download?,
    covers: Boolean,
    onClick: () -> Unit,
) {
    val failed = download is Download.Failed
    val size = offer.bytes.takeIf { it > 0 }?.let { bytes(it) }
    val details =
        listOfNotNull(offer.author.ifBlank { null }, offer.format.uppercase(), size)
    val failure = stringResource(R.string.free_books_failed)
    val icon = if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Download
    val action = if (failed) R.string.retry_item else R.string.download_item
    val label = stringResource(action, offer.title)
    ListItem(
        headlineContent = {
            Text(offer.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val summary = if (failed) failure else details.joinToString(" · ")
            Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { OfferCover(offer.cover.takeIf { covers }) },
        trailingContent = {
            when (download) {
                is Download.Running -> DownloadProgress(download.progress)
                else -> Icon(icon, label)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun OfferCover(url: String?) = Box(
    Modifier.size(36.dp, 54.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
) {
    Icon(
        Icons.AutoMirrored.Outlined.MenuBook,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    url?.let {
        AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun DownloadProgress(value: Float, size: Dp = 24.dp) {
    val progress by animateFloatAsState(value)
    val modifier = Modifier.size(size)
    if (value > 0f) {
        CircularProgressIndicator(progress = { progress }, modifier, strokeWidth = 3.dp)
    } else {
        CircularProgressIndicator(modifier, strokeWidth = 3.dp)
    }
}

@Composable
private fun Note(@StringRes text: Int) = Hint(
    stringResource(text),
    Modifier.padding(horizontal = ScreenPadding + 16.dp).padding(top = 16.dp),
)
