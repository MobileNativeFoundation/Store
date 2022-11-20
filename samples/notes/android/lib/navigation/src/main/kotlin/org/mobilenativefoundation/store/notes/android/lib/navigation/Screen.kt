package org.mobilenativefoundation.store.notes.android.lib.navigation

import androidx.annotation.DrawableRes

private const val HOME_ROUTE = "/home"
private const val ACCOUNT_ROUTE = "/account"
private const val SEARCH_ROUTE = "/search"
private const val SETTINGS_ROUTE = "/settings"
private const val EXPLORE_ROUTE = "/explore"

private const val HOME = "HOME"
private const val ACCOUNT = "ACCOUNT"
private const val SEARCH = "SEARCH"
private const val SETTINGS = "SETTINGS"
private const val EXPLORE = "EXPLORE"


sealed class Screen(
    val route: String,
    val title: String,
    @DrawableRes val iconSelected: Int,
    @DrawableRes val iconNotSelected: Int
) {
    object Home : Screen(HOME_ROUTE, HOME, R.drawable.ic_dig_home_fill, R.drawable.ic_dig_home_line)
    object Account : Screen(ACCOUNT_ROUTE, ACCOUNT, R.drawable.ic_person_fill, R.drawable.ic_dig_person_line)
    object Explore : Screen(EXPLORE_ROUTE, EXPLORE, R.drawable.ic_activity_fill, R.drawable.ic_dig_activity_line)
}