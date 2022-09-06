package com.dropbox.market.notes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dropbox.market.notes.android.fig.FigTheme
import com.dropbox.market.notes.android.fig.color.systemThemeColors
import com.dropbox.market.notes.android.ui.compose.NotesCoordinator
import com.dropbox.market.notes.android.ui.viewmodel.NoteState
import com.dropbox.market.notes.android.ui.viewmodel.NoteViewModel
import com.dropbox.market.notes.android.ui.viewmodel.NoteViewModelFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var noteViewModelFactory: NoteViewModelFactory

    private fun provideNoteViewModel(initialState: NoteState): NoteViewModel {
        return noteViewModelFactory.provide(initialState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FigTheme(colors = systemThemeColors()) {
                NotesCoordinator(noteViewModelProvider = { initialState -> provideNoteViewModel(initialState) })
            }
        }
    }
}