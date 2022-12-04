package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealBookkeeper
import org.mobilenativefoundation.store.store5.impl.RealMarket

/**
 * Tracks when local changes fail to sync with network.
 * @see [RealMarket] usage to persist write request failures and eagerly resolve conflicts before completing a read request.
 */
interface Bookkeeper<Key : Any> {
    suspend fun getTimestampLastFailedSync(key: Key): Long?
    suspend fun setTimestampLastFailedSync(key: Key, timestamp: Long): Boolean
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
