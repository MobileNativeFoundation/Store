package com.dropbox.notes.android.app.ui

import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dropbox.notes.android.app.navigation.BottomTabs
import com.dropbox.notes.android.lib.fig.Fig
import com.dropbox.notes.android.lib.navigation.Screen


@Composable
fun BottomBar(navController: NavHostController) {
    BottomNavigation(
        backgroundColor = Fig.Colors.secondary
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        fun isSelected(tab: Screen) = currentDestination?.hierarchy?.any { it.route == tab.route } == true

        BottomTabs.forEach { tab ->
            BottomNavigationItem(
                icon = {
                    val icon = if (isSelected(tab)) tab.iconSelected else tab.iconNotSelected
                    Icon(painter = painterResource(icon), contentDescription = null)
                },
                selected = isSelected(tab),
                onClick = {
                    navController.navigate(tab.route)
                }
            )
        }
    }
}
