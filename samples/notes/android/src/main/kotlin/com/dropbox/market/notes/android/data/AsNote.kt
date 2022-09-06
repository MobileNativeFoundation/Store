package com.dropbox.market.notes.android.data

import com.dropbox.market.notes.android.Note
import kotlinx.serialization.Serializable


fun MarketNote.asNote() = Note(
    id = id,
    key = key,
    title = title,
    contents = content
)

@Serializable
data class OnComplete(
    val run: suspend () -> Unit
)