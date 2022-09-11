@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.Bookkeeper
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal object BadTestMarket {
    val memoryLruCache = MemoryLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store.by<String, Note, Note>(
        read = { throw Exception() },
        write = { _, _ -> throw Exception() },
        delete = { throw Exception() },
        deleteAll = { throw Exception() }
    )

    private val dbStore = Store.by<String, Note, Note>(
        read = { throw Exception() },
        write = { _, _ -> throw Exception() },
        delete = { throw Exception() },
        deleteAll = { throw Exception() },
    )

    private val bookkeeper = Bookkeeper.by<String>(
        read = { key -> db.getLastWriteTime(key) },
        write = { key, timestamp -> db.setLastWriteTime(key, timestamp) },
        delete = { key -> db.deleteWriteRequest(key) },
        deleteAll = { db.deleteAllWriteRequests() }
    )

    internal fun build() = RealMarket(
        stores = listOf(memoryLruCacheStore, dbStore),
        bookkeeper = bookkeeper
    )
}



