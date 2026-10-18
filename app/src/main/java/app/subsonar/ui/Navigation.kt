package app.subsonar.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.subsonar.R
import app.subsonar.ui.edit.EditScreen
import app.subsonar.ui.imports.ImportRoute
import app.subsonar.ui.insights.InsightsScreen
import app.subsonar.ui.list.SubscriptionListScreen
import app.subsonar.ui.settings.SettingsScreen

private enum class Tab(val route: String, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    LIST("list", R.string.tab_subscriptions, R.drawable.ic_list),
    INSIGHTS("insights", R.string.tab_insights, R.drawable.ic_insights),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_settings),
}

private fun NavHostController.openEdit(id: Long) = navigate("edit/$id")

@Composable
fun SubsonarNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    // Shared so list and insights read the same flow.
    val homeViewModel: HomeViewModel = viewModel(factory = AppViewModelFactory.Factory)

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            nav,
            startDestination = Tab.LIST.route,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            composable(Tab.LIST.route) {
                SubscriptionListScreen(
                    viewModel = homeViewModel,
                    onAdd = { nav.openEdit(0) },
                    onOpen = { nav.openEdit(it) },
                    onImport = { nav.navigate("import") },
                )
            }
            composable(Tab.INSIGHTS.route) { InsightsScreen(viewModel = homeViewModel) }
            composable(Tab.SETTINGS.route) { SettingsScreen(onImport = { nav.navigate("import") }) }
            composable("import") { ImportRoute(onClose = { nav.popBackStack() }) }
            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                EditScreen(onDone = { nav.popBackStack() })
            }
        }
    }
}
