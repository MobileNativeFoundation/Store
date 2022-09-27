package com.dropbox.store.campaigns.android.home.navigation

import com.dropbox.store.campaigns.android.common.navigation.Destinations

sealed class HomeTabDestination {
    object PurchaseFlow : Destinations.PurchaseFlow
}