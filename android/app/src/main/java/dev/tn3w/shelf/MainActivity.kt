package dev.tn3w.shelf

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.*
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
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

class Navigator(private val controller: NavHostController) {
    fun book(book: Book, origin: String) =
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

    fun back() = controller.popBackStack()

    fun tab(route: Any, reselected: Boolean) {
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

class MainViewModel(application: Application) : ShelfViewModel(application) {
    val settings = library.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val openedWork = app.openedWork
    val installConfirmation = app.installConfirmation
    val update = MutableStateFlow<Release?>(null)

    init {
        viewModelScope.launch { update.value = dueUpdate() }
    }

    fun openFile(uri: Uri) = app.openFile(uri)

    fun showLanguage(language: String) = app.showLanguage(language)

    private suspend fun dueUpdate(): Release? {
        val settings = library.settings.first { it.onboarded }
        val now = System.currentTimeMillis()
        val due = now - settings.lastAppCheck > DAY_MILLIS
        val allowed = Updater.supported(app) && settings.checkUpdates &&
            !settings.isOffline(app)
        if (!allowed || !due) return null
        library.updateSettings { it.copy(lastAppCheck = now) }
        val found = runSuspendCatching { Updater.check(app) }.getOrNull()
        return found?.takeIf { now >= it.readyAt }
    }
}

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            viewModel.settings.value == null
        }
        super.onCreate(savedInstanceState)
        transparentBars(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (savedInstanceState == null) openedFile(intent)?.let(viewModel::openFile)
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
                        ShelfNavigation(current)
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
}

@Composable
private fun OpenFile(viewModel: MainViewModel, navigator: Navigator) {
    val opened by viewModel.openedWork.collectAsStateWithLifecycle()
    LaunchedEffect(opened) {
        val work = opened ?: return@LaunchedEffect
        viewModel.openedWork.value = null
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
        viewModel.installConfirmation.value = null
        context.startActivity(intent)
    }
}

@Composable
private fun UpdatePrompt(viewModel: MainViewModel) {
    val release by viewModel.update.collectAsStateWithLifecycle()
    release?.let { UpdateDialog(it) { viewModel.update.value = null } }
}

private fun NavHostController.selectedTab() =
    tabs.indexOfLast { runCatching { getBackStackEntry(it.route) }.isSuccess }
        .coerceAtLeast(0)

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ShelfNavigation(settings: Settings) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    val entry by controller.currentBackStackEntryAsState()
    val selected = remember(entry) { controller.selectedTab() }
    val reading = entry?.destination?.hasRoute(ReaderRoute::class) == true ||
        entry?.destination?.hasRoute(ScanRoute::class) == true
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
        currentWindowAdaptiveInfoV2(),
    )
    val hidden = reading || WindowInsets.isImeVisible
    val viewModel: MainViewModel = viewModel()
    val language = viewModel.bookLanguage(settings)
    LaunchedEffect(language) { viewModel.showLanguage(language) }

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
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                Routes(controller, navigator)
            }
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
