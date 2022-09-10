@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.ConflictResolver
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.ShareableLruCache
import com.dropbox.external.store5.impl.ShareableMarket
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal object OkTestMarket {
    val memoryLruCache = ShareableLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store<String, Note, Note>(
        read = { key -> memoryLruCache.read(key) },
        write = { key, input -> memoryLruCache.write(key, input) },
        delete = { key -> memoryLruCache.delete(key) },
        deleteAll = { memoryLruCache.delete() },
    )

    private val dbStore = Store<String, Note, Note>(
        read = { key -> db.read(key) },
        write = { key, input -> db.write(key, input) },
        delete = { key -> db.delete(key) },
        deleteAll = { db.delete() },
    )

    private val conflictResolver = ConflictResolver<String, Note, Note>(
        setLastFailedWriteTime = { key, updated -> db.setLastWriteTime(key, updated) },
        getLastFailedWriteTime = { key -> db.getLastWriteTime(key) },
        deleteFailedWriteRecord = { key -> db.deleteWriteRequest(key) }
    )

    internal fun build() = ShareableMarket(
        stores = listOf(memoryLruCacheStore, dbStore),
        conflictResolver = conflictResolver
    )
}



