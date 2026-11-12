package dev.tn3w.shelf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.*
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import dev.tn3w.shelf.data.*
import dev.tn3w.shelf.ui.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.ui.graphics.Color as Tint

@Serializable object HomeRoute

@Serializable object LibraryRoute

@Serializable object ExploreRoute

@Serializable object SearchRoute

@Serializable data class BookRoute(val work: Int, val origin: String)

@Serializable object NoBookRoute

@Serializable data class AuthorRoute(val number: Int, val name: String)

@Serializable data class TagRoute(val id: Int)

@Serializable data class SeriesRoute(val work: Int)

@Serializable data class ListRoute(val name: String)

@Keep
enum class SimilarKind { Books, Writer, Saga }

@Serializable
data class SimilarRoute(val kind: SimilarKind, val ids: List<Int>, val name: String = "")

@Serializable data class ReaderRoute(val work: Int, val listen: Boolean = false)

@Serializable object ScanRoute

@Serializable object StatsRoute

@Serializable
data class CatalogRoute(val id: String, val url: String = "", val title: String = "")

@Serializable data class WrappedRoute(val year: Int)

@Serializable data class SettingsRoute(val page: SettingsPage = SettingsPage.Main)

const val OPEN_PLAYER = "dev.tn3w.shelf.OPEN_PLAYER"

private const val SCREEN_FADE_MILLIS = 220
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private data class Tab(@StringRes val label: Int, val icon: ImageVector, val route: Any)

private val tabs = listOf(
    Tab(R.string.home, Icons.AutoMirrored.Rounded.MenuBook, HomeRoute),
    Tab(R.string.library, Icons.Rounded.CollectionsBookmark, LibraryRoute),
    Tab(R.string.explore, Icons.Rounded.Explore, ExploreRoute),
    Tab(R.string.search, Icons.Rounded.Search, SearchRoute),
)
private val DockSpace = 70.dp
private val PlayerSpace = 48.dp
private val DockShape = RoundedCornerShape(29.dp)
private val RailWidth = 84.dp

class Navigator(
    private val controller: NavHostController,
    private val books: NavHostController = controller,
    private val stack: NavHostController = controller,
    private val onTab: (Any) -> Unit = {},
) {
    fun book(book: Book, origin: String) = books.navigate(BookRoute(book.work, origin))

    fun bookPage(book: Book, origin: String) = bookPage(book.work, origin)

    fun bookPage(work: Int, origin: String) = controller.navigate(BookRoute(work, origin))

    fun author(author: Author) =
        controller.navigate(AuthorRoute(author.number, author.name))

    fun tag(id: Int) = controller.navigate(TagRoute(id))

    fun series(work: Int) = controller.navigate(SeriesRoute(work))

    fun list(name: String) = controller.navigate(ListRoute(name))

    fun similar(route: SimilarRoute) = controller.navigate(route)

    fun reader(work: Int, listen: Boolean = false) =
        controller.navigate(ReaderRoute(work, listen)) {
            popUpTo<ReaderRoute> { inclusive = true }
        }

    fun scan() = controller.navigate(ScanRoute) { launchSingleTop = true }

    fun stats() = controller.navigate(StatsRoute) { launchSingleTop = true }

    fun wrapped(year: Int) = controller.navigate(WrappedRoute(year)) {
        launchSingleTop =
            true
    }

    fun settings(page: SettingsPage = SettingsPage.Main) =
        controller.navigate(SettingsRoute(page))

    fun onlineLibraries() = settings(SettingsPage.FreeBooks)

    fun catalog(id: String, url: String = "", title: String = "") =
        controller.navigate(CatalogRoute(id, url, title))

    fun library() = tab(LibraryRoute, reselected = false)

    fun back() = stack.popBackStack()

    fun tab(route: Any, reselected: Boolean) {
        onTab(route)
        if (reselected && controller.popBackStack(route, inclusive = false)) return
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private fun openedFile(intent: Intent): Uri? = when (intent.action) {
    Intent.ACTION_VIEW -> intent.data

    Intent.ACTION_SEND ->
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    else -> null
}

private fun ComponentActivity.transparentBars(style: SystemBarStyle) =
    enableEdgeToEdge(style, style)

class MainViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val settings = library.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val openedWork = app.openedWork
    val installConfirmation = app.installConfirmation
    val messages = app.messages
    val narrator = app.narrator
    val audiobooks = app.audiobooks
    var playerRequested by mutableStateOf(false)
    private val _update = MutableStateFlow<Release?>(null)
    val update = _update.asStateFlow()

    init {
        viewModelScope.launch { _update.value = dueUpdate() }
    }

    fun openFile(uri: Uri) = app.openFile(uri)

    fun consumeOpenedWork() = app.consumeOpenedWork()

    suspend fun hasText(work: Int) = library.hasFile(library.progressOf(work))

    fun consumeInstallConfirmation() = app.confirmInstall(null)

    fun dismissUpdate() {
        _update.value = null
    }

    fun showLanguage(language: String) = app.showLanguage(language)

    private suspend fun dueUpdate(): Release? {
        val settings = library.settings.first { it.onboarded }
        val now = System.currentTimeMillis()
        val due = now - settings.lastAppCheck > DAY_MILLIS
        val allowed = Updater.supported(app) && settings.checkUpdates &&
            !settings.isOffline(app)
        if (!allowed || !due) return null
        val found = runSuspendCatching { Updater.check(app) }.getOrElse { return null }
        library.updateSettings { it.copy(lastAppCheck = now) }
        return found?.takeIf { now >= it.readyAt }
    }
}

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(application as ShelfApp) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            viewModel.settings.value == null
        }
        super.onCreate(savedInstanceState)
        transparentBars(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (savedInstanceState == null) open(intent)
        showMessages()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            val dark = isDark(current)
            LaunchedEffect(dark) {
                transparentBars(
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            CompositionLocalProvider(LocalSettings provides current) {
                ShelfTheme(current) {
                    Box(Modifier.fillMaxSize().background(Look.colors.paper)) {
                        if (current.onboarded) {
                            ShelfNavigation(current, viewModel)
                        } else {
                            OnboardingScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent) {
        if (intent.action == OPEN_PLAYER) viewModel.playerRequested = true
        openedFile(intent)?.let(viewModel::openFile)
    }

    override fun onStop() {
        super.onStop()
        (application as ShelfApp).backUp()
    }

    private fun showMessages() = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun OpenFile(viewModel: MainViewModel, navigator: Navigator) {
    val opened by viewModel.openedWork.collectAsStateWithLifecycle()
    LaunchedEffect(opened) {
        val work = opened ?: return@LaunchedEffect
        if (viewModel.hasText(work)) {
            navigator.reader(work)
        } else {
            navigator.bookPage(work, "opened")
        }
        viewModel.consumeOpenedWork()
    }
}

@Composable
private fun ConfirmInstall(viewModel: MainViewModel) {
    val context = LocalContext.current
    val confirmation by viewModel.installConfirmation
        .collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    LaunchedEffect(confirmation) {
        val intent = confirmation ?: return@LaunchedEffect
        viewModel.consumeInstallConfirmation()
        context.startActivity(intent)
    }
}

@Composable
private fun UpdatePrompt(viewModel: MainViewModel) {
    val release by viewModel.update.collectAsStateWithLifecycle()
    release?.let { UpdateDialog(it, viewModel::dismissUpdate) }
}

private val FULL_SCREEN =
    listOf(ReaderRoute::class, ScanRoute::class, WrappedRoute::class)

private fun NavBackStackEntry.isReading() = FULL_SCREEN.any { destination.hasRoute(it) }

private fun NavHostController.openBook() = currentBackStackEntry
    ?.takeIf { it.destination.hasRoute(BookRoute::class) }
    ?.toRoute<BookRoute>()

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ShelfNavigation(settings: Settings, viewModel: MainViewModel) {
    val controller = rememberNavController()
    val pane = rememberNavController()
    val window = currentWindowAdaptiveInfoV2()
    val wide = window.windowSizeClass
        .isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)
    var selected by rememberSaveable { mutableIntStateOf(0) }
    fun select(route: Any) {
        val index = tabs.indexOfFirst { it.route == route }
        if (index >= 0) selected = index
    }
    val navigator = remember(controller, pane, wide) {
        Navigator(controller, books = if (wide) pane else controller, onTab = ::select)
    }
    val entry by controller.currentBackStackEntryAsState()
    val paneEntry by pane.currentBackStackEntryAsState()
    val reading = entry?.isReading() == true
    val bookOpen = wide && paneEntry?.destination?.hasRoute(BookRoute::class) == true
    val rail = window.windowSizeClass
        .isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
    val hidden = reading || WindowInsets.isImeVisible
    val speech by viewModel.narrator.state.collectAsStateWithLifecycle()
    val audio by viewModel.audiobooks.state.collectAsStateWithLifecycle()
    val playing = listOf(audio, speech).firstOrNull { it.active || it.loading }
    val listening = !hidden && playing != null
    val scope = rememberCoroutineScope()
    fun openPlaying() {
        val work = playing?.work ?: return
        scope.launch {
            if (viewModel.hasText(work)) {
                navigator.reader(work, listen = true)
            } else {
                navigator.bookPage(work, "playing")
            }
        }
    }
    LaunchedEffect(viewModel.playerRequested, playing?.work) {
        if (!viewModel.playerRequested) return@LaunchedEffect
        viewModel.playerRequested = false
        openPlaying()
    }
    val language = viewModel.bookLanguage(settings)
    LaunchedEffect(language) { viewModel.showLanguage(language) }
    LaunchedEffect(entry) {
        val destination = entry?.destination ?: return@LaunchedEffect
        val tab = tabs.firstOrNull { destination.hasRoute(it.route::class) }
        tab?.let { select(it.route) }
    }
    LaunchedEffect(wide, entry) {
        val top = entry ?: return@LaunchedEffect
        if (wide || top.isReading()) return@LaunchedEffect
        val book = pane.openBook() ?: return@LaunchedEffect
        pane.popBackStack(NoBookRoute, inclusive = false)
        controller.navigate(book)
    }
    fun open(index: Int) =
        navigator.tab(tabs[index].route, reselected = index == selected)

    UpdatePrompt(viewModel)
    OpenFile(viewModel, navigator)
    ConfirmInstall(viewModel)
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxSize().windowInsetsPadding(sides)) {
        val dock = when {
            hidden -> 0.dp
            rail -> if (listening) DockSpace else 0.dp
            else -> DockSpace + if (listening) PlayerSpace else 0.dp
        }
        CompositionLocalProvider(LocalDock provides dock) {
            Row {
                if (rail && !hidden) Rail(selected, ::open)
                SharedTransitionLayout(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalSharedScope provides this) {
                        Routes(controller, navigator)
                    }
                }
                if (reading) return@Row
                if (bookOpen) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Look.colors.line))
                }
                val width = if (bookOpen) Modifier.weight(1f) else Modifier.width(0.dp)
                BookPane(controller, pane, width)
            }
        }
        val player: @Composable () -> Unit = {
            NowPlaying(viewModel.narrator, viewModel.audiobooks, ::openPlaying)
        }
        if (rail) {
            if (!listening) return@Box
            return@Box Box(
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .widthIn(max = 420.dp)
                    .floatingBar(),
            ) { player() }
        }
        AnimatedVisibility(
            !hidden,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) { Dock(selected, ::open, player.takeIf { listening }) }
    }
}

@Composable
private fun tabColors(selected: Boolean): Pair<Tint, Tint> {
    val colors = Look.colors
    return when {
        !selected -> Tint.Transparent to colors.soft
        colors.plain -> colors.ink to colors.paper
        else -> colors.wash to colors.ink
    }
}

@Composable
private fun Modifier.floatingBar(): Modifier {
    val lift = if (Look.colors.plain) 0.dp else 16.dp
    return shadow(lift, DockShape).surface(DockShape).blockTouches().padding(5.dp)
}

/** The floating bar that carries the four places of the app and what is playing. */
@Composable
private fun Dock(
    selected: Int,
    onSelect: (Int) -> Unit,
    player: (@Composable () -> Unit)?,
) {
    Column(
        Modifier.navigationBarsPadding()
            .padding(bottom = 12.dp)
            .floatingBar()
            .width(IntrinsicSize.Min),
    ) {
        player?.invoke()
        DockTabs(selected, onSelect)
    }
}

@Composable
private fun DockTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val chosen = index == selected
            val (fill, content) = tabColors(chosen)
            val label = stringResource(tab.label)
            Row(
                Modifier.height(48.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(chosen, role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 16.dp)
                    .animateContentSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, label.takeUnless { chosen }, Modifier.size(22.dp), content)
                if (chosen) Text(label, color = content, style = Look.type.label)
            }
        }
    }
}

@Composable
private fun Rail(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight()
            .width(RailWidth)
            .windowInsetsPadding(WindowInsets.systemBars)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabs.forEachIndexed { index, tab ->
            val chosen = index == selected
            val (fill, content) = tabColors(chosen)
            Column(
                Modifier.clip(Corners.well)
                    .selectable(chosen, role = Role.Tab) { onSelect(index) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(56.dp, 34.dp).background(fill, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(tab.icon, null, Modifier.size(22.dp), content) }
                Text(
                    stringResource(tab.label),
                    color = if (chosen) Look.colors.ink else Look.colors.soft,
                    style = Look.type.note,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BookPane(
    controller: NavHostController,
    pane: NavHostController,
    modifier: Modifier,
) {
    val navigator = remember(controller, pane) { Navigator(controller, pane, pane) }
    NavHost(pane, startDestination = NoBookRoute, modifier) {
        composable<NoBookRoute> {}
        screen<BookRoute> {
            val route = it.toRoute<BookRoute>()
            BookScreen(route.work, route.origin, navigator)
        }
    }
}

@Composable
private fun Routes(controller: NavHostController, navigator: Navigator) {
    val reduced = LocalReducedMotion.current
    val fade = tween<Float>(SCREEN_FADE_MILLIS)
    val enter = if (reduced) EnterTransition.None else fadeIn(fade)
    val exit = if (reduced) ExitTransition.None else fadeOut(fade)
    NavHost(
        controller,
        startDestination = HomeRoute,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
        predictivePopEnterTransition = { enter },
        predictivePopExitTransition = { exit },
    ) {
        screen<HomeRoute> { HomeScreen(navigator) }
        screen<LibraryRoute> { LibraryScreen(navigator) }
        screen<ExploreRoute> { ExploreScreen(navigator) }
        screen<SearchRoute> { SearchScreen(navigator) }
        screen<BookRoute> {
            val route = it.toRoute<BookRoute>()
            BookScreen(route.work, route.origin, navigator)
        }
        screen<AuthorRoute> {
            val route = it.toRoute<AuthorRoute>()
            AuthorScreen(Author(route.number, route.name), navigator)
        }
        screen<TagRoute> { TagScreen(it.toRoute<TagRoute>().id, navigator) }
        screen<SeriesRoute> { SeriesScreen(it.toRoute<SeriesRoute>().work, navigator) }
        screen<ListRoute> { ListScreen(it.toRoute<ListRoute>().name, navigator) }
        screen<SimilarRoute> { SimilarScreen(it.toRoute<SimilarRoute>(), navigator) }
        screen<ReaderRoute> {
            val route = it.toRoute<ReaderRoute>()
            ReaderScreen(route.work, route.listen, navigator)
        }
        screen<ScanRoute> { ScanScreen(navigator) }
        screen<StatsRoute> { StatsScreen(navigator) }
        screen<CatalogRoute> { CatalogScreen(it.toRoute<CatalogRoute>(), navigator) }
        screen<WrappedRoute> { WrappedScreen(it.toRoute<WrappedRoute>().year, navigator) }
        screen<SettingsRoute> {
            SettingsScreen(it.toRoute<SettingsRoute>().page, navigator)
        }
    }
}

private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    CompositionLocalProvider(LocalAnimatedScope provides this) {
        Box(Modifier.fillMaxSize().background(Look.colors.paper)) {
            content(entry)
        }
    }
}
