package com.dropbox.market.notes.android.data.db

import com.dropbox.market.notes.android.Key
import com.dropbox.market.notes.android.NotesDatabase
import com.dropbox.market.notes.android.data.MarketNote
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.asNote

fun NotesDatabase.tryWrite(key: String, input: MarketNote): Boolean {
    return try {
        noteQueries.write(input.asNote())
        true
    } catch (throwable: Throwable) {
        false
    }
}

fun NotesDatabase.tryWrite(key: Key, input: Notebook): Boolean {
    return when (input) {
        is Notebook.Notes -> {
            try {
                input.values.forEach { tryWrite(it.key, it) }
                true
            } catch (throwable: Throwable) {
                false
            }
        }

        is Notebook.Note -> when (input.value) {
            null -> false
            else -> tryWrite(input.value.key, input.value)
        }
    }
}