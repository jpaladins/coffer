// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.nav

import android.net.Uri
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
import app.coffer.unlock.UnlockScreen
import app.coffer.unlock.UnlockViewModel
import app.coffer.vaults.VaultsScreen
import app.coffer.viewer.ViewerScreen
import app.coffer.viewer.ViewerViewModel

object Routes {
    const val VAULTS = "vaults"
    fun unlock(id: String) = "unlock/${Uri.encode(id)}"
    fun browser(id: String) = "browser/${Uri.encode(id)}"
    fun viewer(id: String, path: String) = "viewer/${Uri.encode(id)}?path=${Uri.encode(path)}"
}

/** Navigates to the vault list and drops everything above it (used by auto-lock). */
fun NavHostController.backToVaults() {
    navigate(Routes.VAULTS) {
        popUpTo(Routes.VAULTS) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun CofferNavHost(
    openIds: StateFlow<Set<String>>,
    settings: SecuritySettings,
    nav: NavHostController = rememberNavController(),
) {
    val open by openIds.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    // A locked vault (auto-lock, lock all) must not stay on screen.
    LaunchedEffect(open, entry) {
        val route = entry?.destination?.route.orEmpty()
        val id = entry?.arguments?.getString("id")
        if ((route.startsWith("browser") || route.startsWith("viewer")) && id !in open) nav.backToVaults()
    }
    val autoLockMs by settings.autoLockMs.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    NavHost(nav, startDestination = Routes.VAULTS) {
        composable(Routes.VAULTS) {
            VaultsScreen(
                hiltViewModel(),
                autoLockMs = autoLockMs,
                onAutoLockChange = { ms -> scope.launch { settings.setAutoLockMs(ms) } },
            ) { id, isOpen ->
                nav.navigate(if (isOpen) Routes.browser(id) else Routes.unlock(id))
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
