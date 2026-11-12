package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tn3w.shelf.CatalogRoute
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

private val SERVER_PRESETS = listOf(
    "Calibre-Web" to "https://server:8083/opds",
    "Calibre" to "http://server:8080/opds",
    "Kavita" to "https://server:5000/api/opds/API-KEY",
    "Komga" to "https://server:25600/opds/v1.2/catalog",
    "BookLore" to "https://server:6060/api/v1/opds",
    "Stump" to "https://server:10801/opds/v1.2/catalog",
    "COPS" to "https://server/cops/feed.php",
    "Ubooquity" to "https://server:2202/opds-books/",
)
private val FREE_PRESETS = listOf(
    "Open Library" to "https://openlibrary.org/opds/",
    "unglue.it" to "https://unglue.it/api/opds/",
)
private val WEB_ADDRESS = Regex("""https?://[^\s/]+.*""")

class LibrariesViewModel(app: ShelfApp) : ShelfViewModel(app) {
    fun save(catalog: Catalog) = detached { catalogs.save(catalog) }

    fun remove(id: String) = detached { catalogs.remove(id) }
}

class CatalogViewModel(app: ShelfApp, private val id: String, private val url: String) :
    ShelfViewModel(app) {
    val catalog = catalogs.all.map { all -> all.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _feed = MutableStateFlow<Feed?>(null)
    val feed = _feed.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _files = MutableStateFlow<Map<String, Download>>(emptyMap())
    val files = _files.asStateFlow()

    private fun saved() = catalogs.all.value.firstOrNull { it.id == id }

    private fun client() = saved()?.let(catalogs::client)

    fun authorization(link: String?) = link?.toHttpHost()?.let {
        client()?.authorization(it)
    }

    fun load() {
        val client = client() ?: return
        val shown = _feed.value
        val target = if (shown ==
            null
        ) {
            url.ifEmpty { saved()?.url.orEmpty() }
        } else {
            shown.next
        }
        if (target == null || _loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runSuspendCatching { client.feed(target) }
                .onSuccess { page -> _feed.update { it?.plus(page) ?: page } }
                .onFailure { _error.value = it }
            _loading.value = false
        }
    }

    fun signIn(needed: LoginNeeded, username: String, password: String) {
        val catalog = saved() ?: return
        _error.value = null
        _loading.value = true
        viewModelScope.launch {
            val login = runSuspendCatching {
                catalogs.client(catalog).signIn(needed, username, password)
            }
            login.onSuccess {
                catalogs.save(catalog.copy(logins = catalog.logins + (needed.host to it)))
            }
            _loading.value = false
            login.onFailure { _error.value = (it as? LoginNeeded) ?: needed.failed() }
            if (login.isSuccess) load()
        }
    }

    fun download(entry: Entry, file: Acquisition) {
        val client = client() ?: return
        if (!_files.claim(file.url)) return
        fun report(progress: Float) =
            _files.update { it + (file.url to Download.Running(progress)) }
        viewModelScope.launch {
            val work = transfer(::report) { onProgress ->
                runSuspendCatching { fetch(client, entry, file, onProgress) }
            }
            work.exceptionOrNull()?.takeIf {
                it is LoginNeeded
            }?.let { _error.value = it }
            val done = work.getOrNull() != null
            if (done) {
                report(1f)
                delay(DOWNLOAD_SETTLE_MILLIS)
            }
            _files.update {
                if (done) {
                    it - file.url
                } else {
                    it +
                        (file.url to Download.Failed)
                }
            }
            work.getOrNull()?.let(app::open)
        }
    }

    private suspend fun fetch(
        client: CatalogClient,
        entry: Entry,
        file: Acquisition,
        onProgress: (Float) -> Unit,
    ): Int {
        val target = File.createTempFile("opds", ".${file.format}", app.cacheDir)
        try {
            client.download(file, target, onProgress)
            val find = app::findInCatalogue
            val work = library.importDownload(target, entry.title, entry.author, find)
            return checkNotNull(work)
        } finally {
            target.delete()
        }
    }
}

private fun Feed.plus(page: Feed) =
    copy(entries = entries + page.entries, next = page.next)

private fun String.toHttpHost() = runCatching { URI(this).host }.getOrNull()

@Composable
fun CatalogRows(
    navigator: Navigator,
    settings: Settings,
    update: SettingsUpdate,
    enabled: Boolean,
) {
    val catalogs by shelfViewModel().catalogs.all.collectAsStateWithLifecycle()
    catalogs.forEach { catalog ->
        val on = catalog.id in settings.providers
        fun toggle(value: Boolean) = update {
            val others = it.providers - catalog.id
            it.copy(providers = if (value) others + catalog.id else others)
        }
        SettingRow(
            catalog.name,
            catalog.host,
            enabled = enabled,
            onClick = { navigator.catalog(catalog.id) },
        ) { Switch(checked = on, onCheckedChange = ::toggle, enabled = enabled) }
    }
}

@Composable
fun AddCatalogRow() {
    val viewModel = shelfViewModel(create = ::LibrariesViewModel)
    var adding by rememberSaveable { mutableStateOf(false) }
    ActionRow(R.string.online_library_add, Icons.Outlined.Add) { adding = true }
    if (!adding) return
    CatalogDialog(
        Catalog("", ""),
        isNew = true,
        onDismiss = { adding = false },
        onRemove = {},
        onSave = viewModel::save,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(
    @StringRes title: Int,
    presets: List<Pair<String, String>>,
    onPick: (String, String) -> Unit,
) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (name, url) ->
            SuggestionChip(onClick = { onPick(name, url) }, label = { Text(name) })
        }
    }
}

@Composable
private fun CatalogDialog(
    initial: Catalog,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (Catalog) -> Unit,
) {
    val login = initial.logins[initial.host]
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var username by rememberSaveable { mutableStateOf(login?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(login?.password.orEmpty()) }
    val validUrl = WEB_ADDRESS.matches(url.trim())
    val cleartext = url.trim().startsWith("http://") && username.isNotBlank()

    fun pick(preset: String, address: String) {
        name = preset
        url = address
    }
    fun save() {
        val draft = initial.copy(name = name.trim(), url = url.trim())
        val others = initial.logins - draft.host
        val credentials = Login(username.trim(), password).takeIf {
            username.isNotBlank()
        }
        val kept = login?.takeIf {
            it.token.isNotEmpty() && it.username == username.trim()
        }
        val logins =
            others + listOfNotNull((kept ?: credentials)?.let { draft.host to it })
        onSave(draft.copy(logins = logins))
        onDismiss()
    }
    val heading = if (isNew) R.string.online_library_add else R.string.online_library_edit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(heading)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isNew) {
                    Presets(R.string.online_library_servers, SERVER_PRESETS, ::pick)
                    Presets(R.string.online_library_free, FREE_PRESETS, ::pick)
                }
                Field(R.string.free_books_name, name) { name = it }
                Field(
                    R.string.online_library_url,
                    url,
                    KeyboardType.Uri,
                    error = R.string.online_library_url_invalid.takeIf {
                        url.isNotBlank() && !validUrl
                    },
                ) { url = it }
                Field(R.string.online_library_username, username) { username = it }
                Field(R.string.online_library_password, password, KeyboardType.Password) {
                    password = it
                }
                if (cleartext) {
                    Hint(
                        stringResource(R.string.online_library_cleartext),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = name.isNotBlank() && validUrl) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = {
                        onRemove()
                        onDismiss()
                    }) { Text(stringResource(R.string.remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    type: KeyboardType = KeyboardType.Text,
    @StringRes error: Int? = null,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value,
    onChange,
    Modifier.fillMaxWidth(),
    label = { Text(stringResource(label)) },
    singleLine = true,
    isError = error != null,
    supportingText = error?.let { { Text(stringResource(it)) } },
    keyboardOptions = KeyboardOptions(keyboardType = type),
    visualTransformation = if (type == KeyboardType.Password) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    },
)

@Composable
fun CatalogScreen(route: CatalogRoute, navigator: Navigator) {
    val viewModel = shelfViewModel(key = "${route.id} ${route.url}") {
        CatalogViewModel(it, route.id, route.url)
    }
    val libraries = shelfViewModel(create = ::LibrariesViewModel)
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val offline = loadedSettings()?.isOffline(LocalContext.current) == true
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var signingIn by rememberSaveable { mutableStateOf(true) }
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val needed = error as? LoginNeeded
    val failed = error != null
    LaunchedEffect(offline) { if (!offline && feed == null) viewModel.load() }
    LaunchedEffect(needed) { signingIn = needed != null }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    val title = route.title.ifEmpty { feed?.title.orEmpty() }
        .ifEmpty { catalog?.name.orEmpty() }

    val searchTemplate = feed?.search ?: catalog?.search?.ifEmpty { null }
    val editable = catalog?.takeIf { !it.isBundled && route.url.isEmpty() }

    fun search() {
        val template = searchTemplate ?: return
        if (query.isBlank()) return
        navigator.catalog(route.id, searchUrl(template, query.trim()), query.trim())
    }

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        CatalogBar(
            title,
            navigator::back,
            onEdit = editable?.let { { editing = true } },
            onSearch = searchTemplate?.let { { searching = !searching } },
        )
        if (searching) {
            SearchField(
                query = query,
                onChange = { query = it },
                hint = stringResource(R.string.online_library_search, title),
                modifier = Modifier.focusRequester(focus),
                onSubmit = ::search,
            )
        }
        val current = feed
        when {
            offline -> EmptyState(
                Icons.Outlined.CloudOff,
                stringResource(R.string.online_library_offline),
            )

            needed != null -> SignInPrompt(needed.realm) { signingIn = true }

            current == null && error != null -> Unreachable(viewModel::load)

            current == null -> LoadingBar()

            current.entries.isEmpty() -> EmptyState(
                Icons.Outlined.LocalLibrary,
                stringResource(R.string.no_results),
            )

            else -> Entries(current, loading, failed, viewModel, navigator, route) {
                opened = it
            }
        }
    }
    if (needed != null && signingIn) {
        SignInDialog(needed, onDismiss = { signingIn = false }) { username, password ->
            viewModel.signIn(needed, username, password)
        }
    }
    editable?.takeIf { editing }?.let {
        CatalogDialog(
            it,
            isNew = false,
            onDismiss = { editing = false },
            onRemove = {
                libraries.remove(it.id)
                navigator.back()
            },
            onSave = libraries::save,
        )
    }
    val entry = opened?.let { feed?.entries?.getOrNull(it) } ?: return
    EntrySheet(entry, viewModel) { opened = null }
}

@Composable
private fun CatalogBar(
    title: String,
    onBack: () -> Unit,
    onEdit: (() -> Unit)?,
    onSearch: (() -> Unit)?,
) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val back = stringResource(R.string.back)
    IconAction(Icons.AutoMirrored.Outlined.ArrowBack, back, onBack)
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    onEdit?.let {
        IconAction(Icons.Outlined.Edit, stringResource(R.string.online_library_edit), it)
    }
    onSearch?.let {
        IconAction(Icons.Outlined.Search, stringResource(R.string.search), it)
    }
}

@Composable
private fun Entries(
    feed: Feed,
    loading: Boolean,
    failed: Boolean,
    viewModel: CatalogViewModel,
    navigator: Navigator,
    route: CatalogRoute,
    onOpen: (Int) -> Unit,
) {
    val covers = loadedSettings()?.onlineCovers == true
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(feed.entries) { index, entry ->
            val cover = entry.cover?.takeIf { covers }
            EntryRow(entry, cover, viewModel.authorization(cover)) {
                val link = entry.link
                when {
                    entry.files.isNotEmpty() -> onOpen(index)
                    link != null -> navigator.catalog(route.id, link, entry.title)
                }
            }
        }
        if (feed.next == null) return@LazyColumn
        item {
            if (failed && !loading) return@item Unreachable(viewModel::load)
            LaunchedEffect(feed.entries.size) { viewModel.load() }
            LoadingBar()
        }
    }
}

@Composable
private fun EntryRow(
    entry: Entry,
    cover: String?,
    authorization: String?,
    onClick: () -> Unit,
) {
    val details = entry.author.ifEmpty { entry.summary }
    ListItem(
        headlineContent = {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let {
            { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            if (cover != null || entry.files.isNotEmpty()) {
                RemoteCover(cover, authorization)
            } else {
                Icon(Icons.Outlined.Folder, null)
            }
        },
        trailingContent = {
            val icon = if (entry.files.isEmpty()) {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                Icons.Outlined.Download
            }
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(entry: Entry, viewModel: CatalogViewModel, onDismiss: () -> Unit) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val covers = loadedSettings()?.onlineCovers == true
    val cover = entry.cover?.takeIf { covers }
    ModalBottomSheet(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteCover(cover, viewModel.authorization(cover), DpSize(96.dp, 144.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                    Hint(entry.author)
                }
            }
            if (entry.summary.isNotEmpty()) {
                val style = MaterialTheme.typography.bodyMedium
                Text(entry.summary, style = style, maxLines = 12)
            }
            SettingsGroup {
                entry.files.forEach { file ->
                    FileRow(file, files[file.url]) { viewModel.download(entry, file) }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: Acquisition, download: Download?, onClick: () -> Unit) {
    val failed = download is Download.Failed
    val size = file.bytes.takeIf { it > 0 }?.let { bytes(it) }
    val details = listOfNotNull(file.format.uppercase(), size).joinToString(" · ")
    val summary = if (failed) stringResource(R.string.free_books_failed) else details
    SettingRow(
        file.label.ifEmpty {
            file.format.uppercase()
        },
        summary, onClick = onClick,
    ) {
        when (download) {
            is Download.Running -> DownloadProgress(download.progress)

            else -> Icon(
                if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Download,
                stringResource(R.string.download_item, file.format.uppercase()),
            )
        }
    }
}

@Composable
private fun Unreachable(onRetry: () -> Unit) = Column(
    Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    EmptyState(Icons.Outlined.CloudOff, stringResource(R.string.free_books_unreachable))
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.free_books_retry)) }
}

@Composable
private fun SignInPrompt(realm: String, onSignIn: () -> Unit) = Column(
    Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    EmptyState(Icons.Outlined.Lock, stringResource(R.string.sign_in_to, realm))
    Button(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
}

@Composable
private fun SignInDialog(
    needed: LoginNeeded,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_in_to, needed.realm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needed.failed) {
                    Hint(
                        stringResource(R.string.sign_in_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Field(R.string.online_library_username, username) { username = it }
                Field(R.string.online_library_password, password, KeyboardType.Password) {
                    password = it
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSignIn(username.trim(), password) },
                enabled = username.isNotBlank(),
            ) { Text(stringResource(R.string.sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
