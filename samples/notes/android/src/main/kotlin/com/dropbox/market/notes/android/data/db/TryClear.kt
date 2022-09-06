package com.dropbox.market.notes.android.data.db

import com.dropbox.market.notes.android.NotesDatabase

internal fun NotesDatabase.tryClear(): Boolean {
    return try {
        noteQueries.clear()
        true
    } catch (throwable: Throwable) {
        false
    }
}