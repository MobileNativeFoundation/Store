package org.mobilenativefoundation.store.store5.test_utils.fake

import org.mobilenativefoundation.store.store5.test_utils.model.InputNote
import org.mobilenativefoundation.store.store5.test_utils.model.OutputNote

internal class NotesDatabase {
    private val db: MutableMap<NotesKey, OutputNote?> = mutableMapOf()

    fun put(
        key: NotesKey,
        input: InputNote,
        fail: Boolean = false,
    ): Boolean {
        if (fail) {
            throw Exception()
        }

        db[key] = OutputNote(input.data, input.ttl ?: 0)
        return true
    }

    fun get(
        key: NotesKey,
        fail: Boolean = false,
    ): OutputNote? {
        if (fail) {
            throw Exception()
        }

        return db[key]
    }

    fun clear(
        key: NotesKey,
        fail: Boolean = false,
    ): Boolean {
        if (fail) {
            throw Exception()
        }
        db.remove(key)
        return true
    }

    fun clear(fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        db.clear()
        return true
    }
}
