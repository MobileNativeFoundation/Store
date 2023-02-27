package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.Perishable


internal class OfflineFileCountApi {
    internal val db = mutableMapOf<String, Perishable<Int>>()
    internal var counter = 0

    init {
        seed()
    }

    fun get(key: String, fail: Boolean, ttl: Long?): Perishable<Int> {
        counter += 1
        if (fail) {
            throw Exception()
        }

        val metadata = db[key]!!

        return if (ttl != null) {
            metadata.copy(ttl = ttl)
        } else {
            metadata
        }
    }

    private fun seed() {
        db[Settings.Tag.ID] = Perishable(Settings.Tag.OfflineFiles.OFFLINE_FILE_COUNT)
        counter = 0
    }
}

internal class OfflineFilesStatusApi {
    internal val db = mutableMapOf<String, Perishable<String>>()
    internal var counter = 0

    init {
        seed()
    }

    fun get(key: String, fail: Boolean, ttl: Long?): Perishable<String> {
        counter += 1
        if (fail) {
            throw Exception()
        }

        val metadata = db[key]!!

        return if (ttl != null) {
            metadata.copy(ttl = ttl)
        } else {
            metadata
        }
    }

    private fun seed() {
        db[Settings.Tag.ID] = Perishable(Settings.Tag.OfflineFiles.OFFLINE_FILES_STATUS)
        counter = 0
    }
}
