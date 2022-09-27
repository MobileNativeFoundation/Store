package com.dropbox.notes.android.lib.presentation

import androidx.lifecycle.ViewModel
import com.dropbox.notes.android.lib.presentation.entity.Data
import com.dropbox.notes.android.lib.presentation.entity.Event
import com.dropbox.notes.android.lib.presentation.state.State
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

abstract class ViewModel<E : Event, D : Data>(
    initialState: State<D>,
    protected val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    protected val viewModelScope: CoroutineScope = CoroutineScope(dispatcher)

    private val state = MutableStateFlow(initialState)
    private val events = MutableStateFlow<E?>(null)

    abstract fun present(): MutableStateFlow<State<D>>

    private fun handleEvents() {
        viewModelScope.launch {
            events.filterNotNull().collectLatest { event -> handleEvent(event) }
        }
    }

    protected abstract fun handleEvent(event: E)

    fun emit(event: E) {
        viewModelScope.launch { events.emit(event) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
    }
}
