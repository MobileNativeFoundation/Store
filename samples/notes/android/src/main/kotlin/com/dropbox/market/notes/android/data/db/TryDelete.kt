package com.dropbox.market.notes.android.data.db

import com.dropbox.market.notes.android.NotesDatabase

fun NotesDatabase.tryDelete(key: String): Boolean {
    return try {
        noteQueries.delete(key)
        true
    } catch (throwable: Throwable) {
        false
    }
}
