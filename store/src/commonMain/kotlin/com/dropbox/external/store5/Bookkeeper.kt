package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealBookkeeper

/**
 * Tracks when local changes fail to sync with network.
 */
interface Bookkeeper<Key : Any> {
    suspend fun read(key: Key): Long?
    suspend fun write(key: Key, timestamp: Long): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun deleteAll(): Boolean

    companion object {
        fun <Key : Any> by(
            read: suspend (key: Key) -> Long?,
            write: suspend (key: Key, timestamp: Long) -> Boolean,
            delete: suspend (key: Key) -> Boolean,
            deleteAll: suspend () -> Boolean
        ): Bookkeeper<Key> = RealBookkeeper(read, write, delete, deleteAll)
    }
}