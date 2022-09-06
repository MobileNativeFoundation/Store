package com.dropbox.market.notes.android.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dropbox.market.notes.android.fig.Fig
import com.dropbox.market.notes.android.ui.viewmodel.NoteState
import com.dropbox.market.notes.android.ui.viewmodel.NoteViewModel

@Composable
fun NotebookNote(
    key: String,
    initialState: NoteState,
    viewModelProvider: (initialState: NoteState) -> NoteViewModel
) {

    val viewModel = remember { viewModelProvider(initialState) }

    val state = viewModel.state.collectAsState()

    when (val noteState = state.value) {
        is NoteState.Loading -> Text(text = "Loading")
        is NoteState.Success -> {
            Surface(elevation = 4.dp, modifier = Modifier.padding(12.dp)) {
                Column(
                    modifier = Modifier
                        .background(Fig.Colors.yellow.background)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {

                    BasicTextField(
                        noteState.note.title ?: "Untitled",
                        onValueChange = { viewModel.updateTitle(it) },
                        textStyle = Fig.Typography.titleStandard.copy(
                            color = Fig.Colors.standard.text,
                            fontWeight = FontWeight.Bold
                        ),
                        singleLine = false
                    )
                    BasicTextField(
                        noteState.note.content ?: "",
                        onValueChange = {},
                        textStyle = Fig.Typography.paragraphLarge.copy(
                            color = Fig.Colors.standard.text
                        ),
                        singleLine = false
                    )

                }
            }
        }
    }
}

