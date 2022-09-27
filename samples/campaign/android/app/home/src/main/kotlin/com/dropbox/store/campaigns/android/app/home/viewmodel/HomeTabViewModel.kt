package com.dropbox.store.campaigns.android.home.viewmodel

import com.dropbox.store.campaigns.android.home.entity.HomeTabData
import com.dropbox.store.campaigns.android.home.event.HomeTabEvent
import com.dropbox.store.campaigns.android.common.viewmodel.ViewModel
import com.dropbox.store.campaigns.android.common.viewmodel.state.State
import kotlinx.coroutines.flow.MutableStateFlow

class HomeTabViewModel : ViewModel<HomeTabEvent, HomeTabData>(initialState) {
    override fun present(): MutableStateFlow<State<HomeTabData>> {
        TODO("Not yet implemented")
    }

    override fun handleEvent(event: HomeTabEvent) {
        when (event) {
            is HomeTabEvent.Navigation -> TODO()
        }
    }

    companion object {
        private val initialState = State.Initial
    }
}
