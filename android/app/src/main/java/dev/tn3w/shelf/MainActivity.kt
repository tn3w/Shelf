package dev.tn3w.shelf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.tn3w.shelf.data.*
import dev.tn3w.shelf.ui.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute

@Serializable object LibraryRoute

@Serializable object ExploreRoute

@Serializable object SearchRoute

@Serializable data class BookRoute(val work: Int, val origin: String)

@Serializable object NoBookRoute

@Serializable data class AuthorRoute(val number: Int, val name: String)

@Serializable data class TagRoute(val id: Int)

@Serializable data class SeriesRoute(val work: Int)

@Serializable data class ReaderRoute(val work: Int)

@Serializable object ScanRoute

@Serializable data class SettingsRoute(val page: SettingsPage = SettingsPage.Main)

private const val SCREEN_FADE_MILLIS = 220
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private data class Tab(@StringRes val label: Int, val icon: ImageVector, val route: Any)

private val tabs = listOf(
    Tab(R.string.home, Icons.Outlined.Home, HomeRoute),
    Tab(R.string.library, Icons.AutoMirrored.Outlined.LibraryBooks, LibraryRoute),
    Tab(R.string.explore, Icons.Outlined.Explore, ExploreRoute),
    Tab(R.string.search, Icons.Outlined.Search, SearchRoute),
)

class Navigator(
    private val controller: NavHostController,
    private val books: NavHostController = controller,
    private val stack: NavHostController = controller,
    private val onTab: (Any) -> Unit = {},
) {
    fun book(book: Book, origin: String) = books.navigate(BookRoute(book.work, origin))

    fun bookPage(book: Book, origin: String) =
        controller.navigate(BookRoute(book.work, origin))

    fun author(author: Author) =
        controller.navigate(AuthorRoute(author.number, author.name))

    fun tag(id: Int) = controller.navigate(TagRoute(id))

    fun series(work: Int) = controller.navigate(SeriesRoute(work))

    fun reader(work: Int) = controller.navigate(ReaderRoute(work)) {
        popUpTo<ReaderRoute> { inclusive = true }
    }

    fun scan() = controller.navigate(ScanRoute) { launchSingleTop = true }

    fun settings(page: SettingsPage = SettingsPage.Main) =
        controller.navigate(SettingsRoute(page))

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
    private val _update = MutableStateFlow<Release?>(null)
    val update = _update.asStateFlow()

    init {
        viewModelScope.launch { _update.value = dueUpdate() }
    }

    fun openFile(uri: Uri) = app.openFile(uri)

    fun consumeOpenedWork() = app.consumeOpenedWork()

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
        if (savedInstanceState == null) openedFile(intent)?.let(viewModel::openFile)
        showMessages()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            val dark = isDark(current.theme)
            LaunchedEffect(dark) {
                transparentBars(
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            ShelfTheme(current) {
                Surface(
                    Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                ) {
                    if (current.onboarded) {
                        ShelfNavigation(current, viewModel)
                    } else {
                        OnboardingScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedFile(intent)?.let(viewModel::openFile)
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
        viewModel.consumeOpenedWork()
        navigator.reader(work)
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

private fun NavBackStackEntry.isReading() =
    destination.hasRoute(ReaderRoute::class) || destination.hasRoute(ScanRoute::class)

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
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(window)
    val hidden = reading || WindowInsets.isImeVisible
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

    UpdatePrompt(viewModel)
    OpenFile(viewModel, navigator)
    ConfirmInstall(viewModel)
    NavigationSuiteScaffold(
        layoutType = if (hidden) NavigationSuiteType.None else adaptive,
        navigationSuiteItems = {
            tabs.forEachIndexed { index, tab ->
                item(
                    selected = index == selected,
                    onClick = {
                        navigator.tab(tab.route, reselected = index == selected)
                    },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
    ) {
        Row {
            SharedTransitionLayout(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    Routes(controller, navigator)
                }
            }
            if (reading) return@Row
            if (bookOpen) VerticalDivider()
            val width = if (bookOpen) Modifier.weight(1f) else Modifier.width(0.dp)
            BookPane(controller, pane, width)
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
        screen<ReaderRoute> { ReaderScreen(it.toRoute<ReaderRoute>().work, navigator) }
        screen<ScanRoute> { ScanScreen(navigator) }
        screen<SettingsRoute> {
            SettingsScreen(it.toRoute<SettingsRoute>().page, navigator)
        }
    }
}

private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    CompositionLocalProvider(LocalAnimatedScope provides this) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content(entry)
        }
    }
}
