package com.dropbox.store.fake

import com.dropbox.store.ConflictResolution
import com.dropbox.store.Store
import com.dropbox.store.fake.model.Note
import com.dropbox.store.impl.ShareableLruCache
import com.dropbox.store.impl.ShareableMarket
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

    private val conflictResolution = ConflictResolution.Builder<String, Note, Note>()
        .setLastFailedWriteTime { key, updated -> db.setLastWriteTime(key, updated) }
        .getLastFailedWriteTime { key -> db.getLastWriteTime(key) }
        .deleteFailedWriteRecord { key -> db.deleteWriteRequest(key) }
        .build()

    internal fun build(scope: CoroutineScope) = ShareableMarket(
        scope = scope,
        stores = listOf(memoryLruCacheStore, dbStore),
        conflictResolution = conflictResolution
    )
}



