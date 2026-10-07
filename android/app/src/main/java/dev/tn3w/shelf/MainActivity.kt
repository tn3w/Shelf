package dev.tn3w.shelf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.*
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import dev.tn3w.shelf.data.*
import dev.tn3w.shelf.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable object HomeRoute

@Serializable object LibraryRoute

@Serializable object ExploreRoute

@Serializable object SearchRoute

@Serializable data class BookRoute(val work: Int, val origin: String)

@Serializable data class AuthorRoute(val number: Int, val name: String)

@Serializable data class TagRoute(val id: Int)

@Serializable data class ReaderRoute(val work: Int)

@Serializable data class SettingsRoute(val page: SettingsPage = SettingsPage.Main)

private const val SCREEN_FADE_MILLIS = 220

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

    fun reader(work: Int) =
        controller.navigate(ReaderRoute(work)) { launchSingleTop = true }

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

class MainActivity : ComponentActivity() {
    private val opened = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) opened.value = openedFile(intent)
        val app = application as ShelfApp
        setContent {
            val settings by app.library.settings.collectAsStateWithLifecycle(null)
            val current = settings ?: return@setContent
            val dark = isDark(current.theme)
            LaunchedEffect(dark) {
                val style =
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            ShelfTheme(current) {
                Surface(
                    Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                ) {
                    if (current.onboarded) {
                        ShelfNavigation(current, opened)
                    } else {
                        OnboardingScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedFile(intent)?.let { opened.value = it }
    }
}

@Composable
private fun OpenFile(opened: MutableStateFlow<Uri?>, navigator: Navigator) {
    val app = shelfApp()
    val uri by opened.collectAsStateWithLifecycle()
    val unsupported = stringResource(R.string.unsupported_file)
    LaunchedEffect(uri) {
        val source = uri ?: return@LaunchedEffect
        val work = app.library.importFile(source)
        opened.value = null
        if (work != null) return@LaunchedEffect navigator.reader(work)
        Toast.makeText(app, unsupported, Toast.LENGTH_LONG).show()
    }
}

private fun NavHostController.selectedTab() =
    tabs.indexOfLast { runCatching { getBackStackEntry(it.route) }.isSuccess }
        .coerceAtLeast(0)

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ShelfNavigation(settings: Settings, opened: MutableStateFlow<Uri?>) {
    val controller = rememberNavController()
    val navigator = Navigator(controller)
    val entry by controller.currentBackStackEntryAsState()
    val selected = remember(entry) { controller.selectedTab() }
    val reading = entry?.destination?.hasRoute(ReaderRoute::class) == true
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
        currentWindowAdaptiveInfoV2(),
    )
    val hidden = reading || WindowInsets.isImeVisible
    val app = shelfApp()
    val language = app.bookLanguage(settings)
    LaunchedEffect(language) {
        val current = app.loaded.value ?: return@LaunchedEffect
        if (current.catalogue.language != language) app.reload(language)
    }

    UpdatePrompt(settings)
    OpenFile(opened, navigator)
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
        screen<ReaderRoute> { ReaderScreen(it.toRoute<ReaderRoute>().work, navigator) }
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
