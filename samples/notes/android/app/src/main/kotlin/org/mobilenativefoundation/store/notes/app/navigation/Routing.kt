package org.mobilenativefoundation.store.notes.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.mobilenativefoundation.store.notes.android.feature.account.AccountTab
import org.mobilenativefoundation.store.notes.android.feature.explore.ExploreTab
import org.mobilenativefoundation.store.notes.android.feature.home.HomeTab
import org.mobilenativefoundation.store.notes.android.lib.navigation.Screen

@Composable
fun Routing(navController: NavHostController, innerPadding: PaddingValues) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = Modifier.padding(innerPadding)
    ) {
        composable(Screen.Home.route) { HomeTab() }
        composable(Screen.Explore.route) { ExploreTab() }
        composable(Screen.Account.route) { AccountTab() }
    }
}