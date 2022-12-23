package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.SOTNote

internal class NotesDatabase {
    private val db: MutableMap<String, SOTNote?> = mutableMapOf()
    fun put(key: String, input: SOTNote, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }

        db[key] = input
        return true
    }

    fun get(key: String, fail: Boolean = false): SOTNote? {
        if (fail) {
            throw Exception()
        }

        return db[key]
    }

    fun clear(key: String, fail: Boolean = false): Boolean {
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
