package dev.tn3w.shelf

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.tn3w.shelf.data.Author
import dev.tn3w.shelf.data.Book
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.ui.AboutScreen
import dev.tn3w.shelf.ui.AuthorScreen
import dev.tn3w.shelf.ui.BookScreen
import dev.tn3w.shelf.ui.ExploreScreen
import dev.tn3w.shelf.ui.HomeScreen
import dev.tn3w.shelf.ui.LibraryScreen
import dev.tn3w.shelf.ui.LocalAnimatedScope
import dev.tn3w.shelf.ui.LocalReducedMotion
import dev.tn3w.shelf.ui.LocalSharedScope
import dev.tn3w.shelf.ui.OnboardingScreen
import dev.tn3w.shelf.ui.ReaderScreen
import dev.tn3w.shelf.ui.SearchScreen
import dev.tn3w.shelf.ui.SettingsScreen
import dev.tn3w.shelf.ui.ShelfTheme
import dev.tn3w.shelf.ui.TagScreen
import dev.tn3w.shelf.ui.UpdatePrompt
import dev.tn3w.shelf.ui.isDark
import kotlinx.serialization.Serializable

@Serializable object HomeRoute

@Serializable object LibraryRoute

@Serializable object ExploreRoute

@Serializable object SearchRoute

@Serializable data class BookRoute(val work: Int, val origin: String)

@Serializable data class AuthorRoute(val number: Int, val name: String)

@Serializable data class TagRoute(val id: Int)

@Serializable data class ReaderRoute(val work: Int)

@Serializable object SettingsRoute

@Serializable object AboutRoute

private const val ScreenFadeMillis = 220

private data class Tab(@StringRes val label: Int, val icon: ImageVector, val route: Any)

private val tabs =
    listOf(
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

    fun settings() = controller.navigate(SettingsRoute)

    fun about() = controller.navigate(AboutRoute)

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.onIntent(this, intent)
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
            ShelfTheme(dark) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (current.onboarded) ShelfNavigation(current)
                    else OnboardingScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Updater.onIntent(this, intent)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ShelfNavigation(settings: Settings) {
    val controller = rememberNavController()
    val navigator = Navigator(controller)
    val entry by controller.currentBackStackEntryAsState()
    val hierarchy = entry?.destination?.hierarchy.orEmpty()
    val onTab =
        tabs.indexOfFirst { tab -> hierarchy.any { it.hasRoute(tab.route::class) } }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (onTab >= 0 && onTab != selected) selected = onTab
    val reading = hierarchy.any { it.hasRoute(ReaderRoute::class) }
    val adaptive =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
            currentWindowAdaptiveInfoV2()
        )
    val hidden = reading || WindowInsets.isImeVisible

    UpdatePrompt(settings)
    NavigationSuiteScaffold(
        layoutType = if (hidden) NavigationSuiteType.None else adaptive,
        navigationSuiteItems = {
            tabs.forEachIndexed { index, tab ->
                item(
                    selected = index == selected,
                    onClick = {
                        navigator.tab(tab.route, reselected = index == selected)
                        selected = index
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
    val fade = tween<Float>(ScreenFadeMillis)
    NavHost(
        controller,
        startDestination = HomeRoute,
        enterTransition = { if (reduced) EnterTransition.None else fadeIn(fade) },
        exitTransition = { if (reduced) ExitTransition.None else fadeOut(fade) },
        popEnterTransition = { if (reduced) EnterTransition.None else fadeIn(fade) },
        popExitTransition = { if (reduced) ExitTransition.None else fadeOut(fade) },
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
        screen<SettingsRoute> { SettingsScreen(navigator) }
        screen<AboutRoute> { AboutScreen(navigator) }
    }
}

private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit
) =
    composable<T> { entry ->
        CompositionLocalProvider(LocalAnimatedScope provides this) { content(entry) }
    }
