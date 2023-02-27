package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.Setting

internal class UnprocessedSettingDatabase {
    private val db: MutableMap<String, Setting.Unprocessed?> = mutableMapOf()
    fun put(key: String, input: Setting.Unprocessed, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()

        }

        db[key] = input
        return true
    }

    fun get(key: String, fail: Boolean = false): Setting.Unprocessed? {
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
