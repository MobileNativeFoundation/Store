package com.atlas.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    class Loaded(val user: com.atlas.api.UserDto) : ProfileUiState
    class Failed(val error: Throwable) : ProfileUiState
}

class ProfileViewModel(private val api: com.atlas.api.AtlasApi) : ViewModel() {
    val state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)

    fun load(userId: String) {
        viewModelScope.launch {
            state.value = try {
                ProfileUiState.Loaded(api.getUser(userId))
            } catch (t: Throwable) {
                ProfileUiState.Failed(t)
            }
        }
    }
    // TODO: offline cache, pull-to-refresh, sign-out wipe, push-driven staleness
}
