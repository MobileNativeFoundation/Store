package com.dropbox.external.store5.fake

import com.dropbox.external.store5.ConflictResolver
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.ShareableLruCache
import com.dropbox.external.store5.impl.ShareableMarket
import kotlinx.coroutines.CoroutineScope

internal object OkTestMarket {
    val memoryLruCache = ShareableLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store.Builder<String, Note, Note>()
        .read { key -> memoryLruCache.read(key) }
        .write { key, input -> memoryLruCache.write(key, input) }
        .delete { key -> memoryLruCache.delete(key) }
        .clear { memoryLruCache.clear() }
        .build()

    private val dbStore = Store.Builder<String, Note, Note>()
        .read { key -> db.read(key) }
        .write { key, input -> db.write(key, input) }
        .delete { key -> db.delete(key) }
        .clear { db.clear() }
        .build()

    private val conflictResolver = ConflictResolver<String, Note, Note>(
        setLastFailedWriteTime = { key, updated -> db.setLastWriteTime(key, updated) },
        getLastFailedWriteTime = { key -> db.getLastWriteTime(key) },
        deleteFailedWriteRecord = { key -> db.deleteWriteRequest(key) }
    )

    internal fun build(scope: CoroutineScope) = ShareableMarket(
        scope = scope,
        stores = listOf(memoryLruCacheStore, dbStore),
        conflictResolver = conflictResolver
    )
}



