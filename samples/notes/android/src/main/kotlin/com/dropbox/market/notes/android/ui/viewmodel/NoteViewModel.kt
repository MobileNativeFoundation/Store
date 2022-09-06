@file:Suppress("UNCHECKED_CAST")

package com.dropbox.market.notes.android.ui.viewmodel

import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.data.MarketNote
import com.dropbox.market.notes.android.data.NoteOrigin
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.asMarketNote
import com.dropbox.market.notes.android.data.asNoteStateOrigin
import com.dropbox.market.notes.android.data.repository.NotesRepository
import com.dropbox.store.Market
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch

sealed class NoteState {
    abstract val key: Key.Single

    data class Success(
        override val key: Key.Single,
        val note: MarketNote,
        val origin: NoteOrigin
    ) : NoteState()

    data class Loading(
        override val key: Key.Single,
    ) : NoteState()
}

class NoteViewModel @AssistedInject constructor(
    @Assisted initialState: NoteState,
    private val coroutineDispatcher: CoroutineDispatcher,
    private val repository: NotesRepository
) {
    private val coroutineScope = CoroutineScope(coroutineDispatcher)

    val state: MutableStateFlow<NoteState> = MutableStateFlow(initialState)

    init {
        coroutineScope.launch {
            state.value = read(initialState.key).last()
        }
    }

    private fun read(key: Key): Flow<NoteState> = channelFlow {
        repository.read(key).collectLatest { response ->
            if (response is Market.Response.Success) {
                val note = when (val notebook = response.value) {
                    is Notebook.Note -> notebook.value
                    is Notebook.Notes -> null
                }

                if (note != null) {
                    send(
                        NoteState.Success(
                            key as Key.Single,
                            note,
                            response.origin.asNoteStateOrigin()
                        )
                    )
                }
            }
        }
    }

    fun updateTitle(title: String) {
        when (val previousState = state.value) {
            is NoteState.Loading -> return
            is NoteState.Success -> {

                val updatedNote = previousState.note.copy(title = title)
                val nextState = previousState.copy(note = updatedNote)

                state.value = nextState

                coroutineScope.launch {
                    updateMarket(nextState.key, updatedNote)
                }
            }
        }
    }

    private fun updateMarket(key: Key.Single, note: MarketNote) {
        coroutineScope.launch {
            repository.post(key, Notebook.Note(note))
        }
    }

    private fun get(key: Key): Flow<MarketNote> = channelFlow {
        repository.read(key).collect { notebook ->
            when (notebook) {
                Market.Response.Empty -> {}
                is Market.Response.Failure -> {}
                Market.Response.Loading -> {}
                is Market.Response.Success -> when (notebook.value) {
                    is Notebook.Note -> send((notebook.value as Notebook.Note).asMarketNote())
                    is Notebook.Notes -> {}
                }
            }
        }

    }
}

@AssistedFactory
interface NoteViewModelFactory {
    fun provide(initialState: NoteState): NoteViewModel
}
