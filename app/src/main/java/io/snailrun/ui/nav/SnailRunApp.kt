package io.snailrun.ui.nav

import androidx.compose.foundation.layout.padding
import io.snailrun.R
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

enum class TopLevel(val route: String, val label: String, @DrawableRes val icon: Int) {
    Record("record", "Record", R.drawable.ic_snail),
    History("history", "Runs", R.drawable.ic_list),
    Coach("coach", "Coach", R.drawable.ic_coach),
    Settings("settings", "Settings", R.drawable.ic_settings),
}

/**
 * The guided strength session. A push, not a tab, and without the bottom bar: it is an
 * activity you are in rather than a place you are at, and a nav bar under it is four
 * invitations to lose your place in a session you are two exercises into.
 */
const val ROUTE_STRENGTH = "strength"

const val ROUTE_RUN_DETAIL = "run/{runId}"
const val ROUTE_RUN_MAP = "run/{runId}/map"

fun runDetailRoute(runId: Long) = "run/$runId"

fun runMapRoute(runId: Long) = "run/$runId/map"

@Composable
fun SnailRunScaffold(
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    Scaffold(
        bottomBar = {
            // The bar hides on the detail screen: that one is a push, not a tab.
            if (TopLevel.entries.any { top -> currentRoute?.hierarchy?.any { it.route == top.route } == true }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevel.entries.forEach { destination ->
                        val selected =
                            currentRoute?.hierarchy?.any { it.route == destination.route } == true
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
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { insets ->
        content(Modifier.padding(insets))
    }
}
