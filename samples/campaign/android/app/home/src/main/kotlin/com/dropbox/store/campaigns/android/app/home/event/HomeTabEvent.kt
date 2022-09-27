package com.dropbox.store.campaigns.android.home.event

import com.dropbox.store.campaigns.android.home.navigation.HomeTabDestination
import com.dropbox.store.campaigns.android.common.viewmodel.event.Event
import com.dropbox.store.campaigns.android.common.viewmodel.navigation.Destination

sealed class HomeTabEvent : Event {
    data class Navigation(
        override val destination: Destination<HomeTabDestination>
    ) : HomeTabEvent(), Event.Navigation<HomeTabDestination>
}