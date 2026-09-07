package dev.sieve.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.sieve.R
import dev.sieve.filter.BlocklistRepository
import dev.sieve.ui.screens.ActivityScreen
import dev.sieve.ui.screens.HomeScreen
import dev.sieve.ui.screens.ListsScreen
import dev.sieve.ui.screens.RulesScreen
import dev.sieve.ui.screens.SettingsScreen

private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_status, Icons.Filled.Shield),
    LISTS("lists", R.string.tab_lists, Icons.AutoMirrored.Filled.List),
    RULES("rules", R.string.tab_rules, Icons.Filled.Rule),
    ACTIVITY("activity", R.string.tab_activity, Icons.Filled.History),
}

private const val SETTINGS_ROUTE = "settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SieveRoot(
    onRequestStart: () -> Unit,
    viewModel: SieveViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val failureMessage = (updateState as? BlocklistRepository.UpdateState.Failed)?.message

    LaunchedEffect(failureMessage) {
        if (failureMessage != null) {
            snackbarHostState.showSnackbar("Could not update lists: $failureMessage")
            viewModel.dismissUpdateError()
        }
    }

    val onSettings = remember(navController) {
        {
            navController.navigate(SETTINGS_ROUTE) {
                launchSingleTop = true
            }
            Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.tab_settings))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(viewModel = viewModel, onRequestStart = onRequestStart)
            }
            composable(Destination.LISTS.route) { ListsScreen(viewModel) }
            composable(Destination.RULES.route) { RulesScreen(viewModel) }
            composable(Destination.ACTIVITY.route) { ActivityScreen(viewModel) }
            composable(SETTINGS_ROUTE) { SettingsScreen(viewModel) }
        }
    }
}
