package com.dropbox.notes.android.app.ui

import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.dropbox.notes.android.app.navigation.Routing


@Composable
fun Scaffold() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { BottomBar(navController = navController) }
    ) { innerPadding ->
        Routing(navController = navController, innerPadding = innerPadding)
    }
}