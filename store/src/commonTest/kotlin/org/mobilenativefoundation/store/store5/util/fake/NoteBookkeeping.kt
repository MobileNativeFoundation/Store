package org.mobilenativefoundation.store.store5.util.fake

class NoteBookkeeping {
    private val log: MutableMap<String, Long?> = mutableMapOf()
    fun setLastFailedSync(key: String, timestamp: Long, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        log[key] = timestamp
        return true
    }

    fun getLastFailedSync(key: String, fail: Boolean = false): Long? {
        if (fail) {
            throw Exception()
        }

        return log[key]
    }

    fun clear(key: String, fail: Boolean = false): Boolean {
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
