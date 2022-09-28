@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.Bookkeeper
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal object OkTestMarket {
    val memoryLruCache = MemoryLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store.by<String, Note, Note>(
        reader = { key -> memoryLruCache.read(key) },
        writer = { key, input -> memoryLruCache.write(key, input) },
        deleter = { key -> memoryLruCache.delete(key) },
        clearer = { memoryLruCache.deleteAll() },
    )

    private val dbStore = Store.by<String, Note, Note>(
        reader = { key -> db.read(key) },
        writer = { key, input -> db.write(key, input) },
        deleter = { key -> db.delete(key) },
        clearer = { db.deleteAll() },
    )

    private val bookkeeper = Bookkeeper.by<String>(
        read = { key -> BadTestMarket.db.getLastWriteTime(key) },
        write = { key, timestamp -> BadTestMarket.db.setLastWriteTime(key, timestamp) },
        delete = { key -> BadTestMarket.db.deleteWriteRequest(key) },
        deleteAll = { BadTestMarket.db.deleteAllWriteRequests() }
    )

    internal fun build() = RealMarket(
        stores = listOf(memoryLruCacheStore, dbStore),
        bookkeeper = bookkeeper
    )
}



