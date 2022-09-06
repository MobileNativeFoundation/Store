package com.dropbox.market.notes.android.data

import com.dropbox.market.notes.android.Note

fun Note.asMarketNote() = MarketNote(
    id = id.toString(),
    key = key!!,
    title = title,
    content = contents,
    origin = NoteOrigin.Store
)

fun Notebook.Note.asMarketNote() = MarketNote(
    id = value!!.id,
    key = value.key,
    title = value.title,
    content = value.content,
    origin = NoteOrigin.Store
)