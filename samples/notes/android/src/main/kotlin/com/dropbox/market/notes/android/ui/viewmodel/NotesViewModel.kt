package com.dropbox.market.notes.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.data.MarketNote
import com.dropbox.market.notes.android.data.NoteOrigin
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.asNoteStateOrigin
import com.dropbox.market.notes.android.data.repository.NotesRepository
import com.dropbox.store.Market
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class NotesState {

    data class Success(
        val notes: MutableMap<String, MarketNote>,
        val origin: NoteOrigin
    ) : NotesState()

    object Loading : NotesState()
    object Initial : NotesState()
}

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val coroutineDispatcher: CoroutineDispatcher
) : ViewModel() {

    val scope = CoroutineScope(coroutineDispatcher)

    private val keys = MutableStateFlow(Key.All)

    val state: StateFlow<NotesState> =
        read(keys.value).stateIn(viewModelScope, SharingStarted.Lazily, NotesState.Initial)

    val isOffline: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val statusAsync = async { status() }
            val status = statusAsync.await()
            isOffline.value = status != 200
        }
    }

    private suspend fun get(key: Key) = repository.get(key)
    private suspend fun status() = repository.getStatus()

    private fun MutableSharedFlow<Market.Response<Notebook>>.asNotesState() = map {
        when (it) {
            Market.Response.Empty -> NotesState.Loading
            is Market.Response.Failure -> NotesState.Loading
            Market.Response.Loading -> NotesState.Loading
            is Market.Response.Success -> {
                NotesState.Success(it.value.foldIntoMutableMap(), origin = it.origin.asNoteStateOrigin())
            }
        }
    }

    private fun Flow<Market.Response<Notebook>>.asNotesState() = map {
        when (it) {
            Market.Response.Empty -> NotesState.Loading
            is Market.Response.Failure -> NotesState.Loading
            Market.Response.Loading -> NotesState.Loading
            is Market.Response.Success -> {
                NotesState.Success(it.value.foldIntoMutableMap(), origin = it.origin.asNoteStateOrigin())
            }
        }
    }

    private fun Notebook.foldIntoMutableMap(): MutableMap<String, MarketNote> {
        return when (this) {
            is Notebook.Note -> {
                val nextMap = mutableMapOf<String, MarketNote>()
                nextMap[this.value!!.key] = this.value
                nextMap
            }

            is Notebook.Notes -> {
                val nextMap = mutableMapOf<String, MarketNote>()
                this.values.forEach { nextMap[it.key] = it }
                nextMap
            }
        }
    }

    private fun read(key: Key): Flow<NotesState> = channelFlow {
        repository.read(key).collectLatest {
            if (it is Market.Response.Success) {
                send(NotesState.Success(it.value.foldIntoMutableMap(), it.origin.asNoteStateOrigin()))
            }
        }
    }

    private fun Array<Market.Response<Notebook>>.foldIntoMutableMap(): MutableMap<String, MarketNote> =
        fold(mutableMapOf()) { map, response ->
            map.apply {
                when (response) {
                    Market.Response.Empty -> TODO()
                    is Market.Response.Failure -> TODO()
                    Market.Response.Loading -> TODO()
                    is Market.Response.Success -> {
                        when (val notebook = response.value) {
                            is Notebook.Note -> if (notebook.value != null) this[notebook.value.key] = notebook.value
                            is Notebook.Notes -> notebook.values.forEach { this[it.key] = it }
                        }
                    }
                }
            }
        }
}
