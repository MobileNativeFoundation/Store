package com.dropbox.notes.android.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.dropbox.notes.android.feature.account.AccountTab
import com.dropbox.notes.android.feature.explore.ExploreTab
import com.dropbox.notes.android.feature.home.HomeTab
import com.dropbox.notes.android.lib.navigation.Screen

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