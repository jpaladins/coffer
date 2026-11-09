// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.nav

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import app.coffer.ui.components.rememberAnimationsEnabled
import app.coffer.onboarding.OnboardingExit
import app.coffer.settings.UiPrefs
import app.coffer.onboarding.OnboardingScreen
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import app.coffer.security.SecuritySettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.coffer.browser.BrowserScreen
import app.coffer.browser.BrowserViewModel
import app.coffer.create.CreateScreen
import app.coffer.security.AppLock
import app.coffer.settings.SettingsScreen
import app.coffer.about.AboutScreen
import app.coffer.unlock.UnlockScreen
import app.coffer.unlock.UnlockViewModel
import app.coffer.vaults.VaultsScreen
import app.coffer.viewer.ViewerScreen
import app.coffer.viewer.ViewerViewModel

object Routes {
    const val VAULTS = "vaults"
    const val ONBOARDING = "onboarding"
    const val CREATE = "create"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    fun unlock(id: String) = "unlock/${Uri.encode(id)}"
    fun browser(id: String) = "browser/${Uri.encode(id)}"
    fun viewer(id: String, path: String) = "viewer/${Uri.encode(id)}?path=${Uri.encode(path)}"
}

/** Set on the list entry to open the document picker once (onboarding "Add container"). */
private const val KEY_PICK = "pick_container"

/** Navigates to the vault list and drops everything above it (used by auto-lock). */
fun NavHostController.backToVaults() {
    navigate(Routes.VAULTS) {
        popUpTo(Routes.VAULTS) { inclusive = true }
        launchSingleTop = true
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CofferNavHost(
    openIds: StateFlow<Set<String>>,
    settings: SecuritySettings,
    appLock: AppLock,
    biometricAvailable: Boolean,
    nav: NavHostController = rememberNavController(),
    startDestination: String = Routes.VAULTS,
    onOnboardingDone: () -> Unit = {},
    uiPrefs: UiPrefs? = null,
) {
    val open by openIds.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    // A locked vault (auto-lock, lock all) must not stay on screen.
    LaunchedEffect(open, entry) {
        val route = entry?.destination?.route.orEmpty()
        val id = entry?.arguments?.getString("id")
        if ((route.startsWith("browser") || route.startsWith("viewer")) && id !in open) nav.backToVaults()
    }
    val animate = rememberAnimationsEnabled()
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    NavHost(
        nav,
        startDestination = startDestination,
        enterTransition = { if (animate) slideInHorizontally(slide) { it / 4 } + fadeIn(fade) else EnterTransition.None },
        exitTransition = { if (animate) slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade) else ExitTransition.None },
        popEnterTransition = { if (animate) slideInHorizontally(slide) { -it / 4 } + fadeIn(fade) else EnterTransition.None },
        popExitTransition = { if (animate) slideOutHorizontally(slide) { it / 4 } + fadeOut(fade) else ExitTransition.None },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen { exit ->
                onOnboardingDone()
                nav.navigate(Routes.VAULTS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                when (exit) {
                    OnboardingExit.AddExisting -> nav.currentBackStackEntry?.savedStateHandle?.set(KEY_PICK, true)
                    OnboardingExit.CreateNew -> nav.navigate(Routes.CREATE)
                    OnboardingExit.Skip -> Unit
                }
            }
        }
        composable(Routes.VAULTS) { entry ->
            val pickNow = remember { entry.savedStateHandle.remove<Boolean>(KEY_PICK) == true }
            VaultsScreen(
                hiltViewModel(),
                openPicker = pickNow,
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onCreate = { nav.navigate(Routes.CREATE) },
            ) { id, isOpen ->
                nav.navigate(if (isOpen) Routes.browser(id) else Routes.unlock(id))
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(settings, appLock, biometricAvailable, onBack = { nav.popBackStack() }, uiPrefs = uiPrefs) {
                nav.navigate(Routes.ABOUT)
            }
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CREATE) {
            CreateScreen(hiltViewModel(), onBack = { nav.popBackStack() }) { vaultId ->
                nav.navigate(Routes.browser(vaultId)) { popUpTo(Routes.VAULTS) }
            }
        }
        composable("unlock/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm = hiltViewModel<UnlockViewModel, UnlockViewModel.Factory>(creationCallback = { it.create(id) })
            UnlockScreen(vm, onBack = { nav.popBackStack() }) { vaultId ->
                nav.navigate(Routes.browser(vaultId)) { popUpTo(Routes.VAULTS) }
            }
        }
        composable("browser/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm = hiltViewModel<BrowserViewModel, BrowserViewModel.Factory>(creationCallback = { it.create(id) })
            BrowserScreen(
                vm,
                onExit = { nav.popBackStack(Routes.VAULTS, inclusive = false) },
                onOpenFile = { path -> nav.navigate(Routes.viewer(id, path)) },
            )
        }
        composable(
            "viewer/{id}?path={path}",
            listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "/" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val path = entry.arguments?.getString("path").orEmpty()
            val vm = hiltViewModel<ViewerViewModel, ViewerViewModel.Factory>(creationCallback = { it.create(id, path) })
            ViewerScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
