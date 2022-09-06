package com.dropbox.market.notes.android.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.fig.Fig
import com.dropbox.market.notes.android.ui.viewmodel.NoteState
import com.dropbox.market.notes.android.ui.viewmodel.NoteViewModel
import com.dropbox.market.notes.android.ui.viewmodel.NotesState
import com.dropbox.market.notes.android.ui.viewmodel.NotesViewModel

@Composable
fun NotesCoordinator(
    noteViewModelProvider: (initialState: NoteState) -> NoteViewModel,
    notesViewModel: NotesViewModel = hiltViewModel(),
) {
    val state = notesViewModel.state.collectAsState()
    val isOfflineState = notesViewModel.isOffline.collectAsState()

    Column {
        StatusBanner(state.value, isOfflineState.value)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Fig.Colors.standard.background)
                .padding(horizontal = 56.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val value = state.value) {
                NotesState.Loading -> {}
                is NotesState.Success -> {

                    items(value.notes.values.toList()) {
                        NotebookNote(
                            key = it.key,
                            initialState = NoteState.Success(
                                Key.Single(it.key), it, it.origin
                            ), viewModelProvider = noteViewModelProvider
                        )
                    }
                }

                NotesState.Initial -> {}
            }
        }
    }
}