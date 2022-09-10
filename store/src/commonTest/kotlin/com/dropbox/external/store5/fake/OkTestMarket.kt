@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.ConflictResolver
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal object OkTestMarket {
    val memoryLruCache = MemoryLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store.by<String, Note, Note>(
        read = { key -> memoryLruCache.read(key) },
        write = { key, input -> memoryLruCache.write(key, input) },
        delete = { key -> memoryLruCache.delete(key) },
        deleteAll = { memoryLruCache.deleteAll() },
    )

    private val dbStore = Store.by<String, Note, Note>(
        read = { key -> db.read(key) },
        write = { key, input -> db.write(key, input) },
        delete = { key -> db.delete(key) },
        deleteAll = { db.deleteAll() },
    )

    private val conflictResolver = ConflictResolver<String, Note, Note>(
        setLastFailedWriteTime = { key, updated -> db.setLastWriteTime(key, updated) },
        getLastFailedWriteTime = { key -> db.getLastWriteTime(key) },
        deleteFailedWriteRecord = { key -> db.deleteWriteRequest(key) }
    )

    internal fun build() = RealMarket(
        stores = listOf(memoryLruCacheStore, dbStore),
        conflictResolver = conflictResolver
    )
}



