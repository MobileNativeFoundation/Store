package org.mobilenativefoundation.store.store5.util.fake

class NotesBookkeeping {
    private val log: MutableMap<NotesKey, Long?> = mutableMapOf()
    fun setLastFailedSync(key: NotesKey, timestamp: Long, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        log[key] = timestamp
        return true
    }

    fun getLastFailedSync(key: NotesKey, fail: Boolean = false): Long? {
        if (fail) {
            throw Exception()
        }

        return log[key]
    }

    fun clear(key: NotesKey, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        log.remove(key)
        return true
    }

    fun clear(fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        log.clear()
        return true
    }
}
