package com.dropbox.market.notes.android.data

import com.dropbox.store.Market

fun Market.Response.Companion.Origin.asNoteStateOrigin(): NoteOrigin = when (this) {
    Market.Response.Companion.Origin.Store -> NoteOrigin.Store
    Market.Response.Companion.Origin.Remote -> NoteOrigin.Remote
    Market.Response.Companion.Origin.LocalWrite -> NoteOrigin.LocalWrite
}