package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealBookkeeper
import org.mobilenativefoundation.store.store5.impl.extensions.now

/**
 * Tracks when local changes fail to sync with network.
 * @see [RealMarket] usage to persist write request failures and eagerly resolve conflicts before completing a read request.
 */
interface Bookkeeper<Key : Any> {
    suspend fun getLastFailedSync(key: Key): Long?
    suspend fun setLastFailedSync(key: Key, timestamp: Long = now()): Boolean
    suspend fun clear(key: Key): Boolean
    suspend fun clearAll(): Boolean

    companion object {
        fun <Key : Any> by(
            getLastFailedSync: suspend (key: Key) -> Long?,
            setLastFailedSync: suspend (key: Key, timestamp: Long) -> Boolean,
            clear: suspend (key: Key) -> Boolean,
            clearAll: suspend () -> Boolean
        ): Bookkeeper<Key> = RealBookkeeper(getLastFailedSync, setLastFailedSync, clear, clearAll)
    }
}
