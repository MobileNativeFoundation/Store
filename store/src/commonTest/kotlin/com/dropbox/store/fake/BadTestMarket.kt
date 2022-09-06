package com.dropbox.store.fake

import com.dropbox.store.ConflictResolution
import com.dropbox.store.Store
import com.dropbox.store.fake.model.Note
import com.dropbox.store.impl.ShareableLruCache
import com.dropbox.store.impl.ShareableMarket
import kotlinx.coroutines.CoroutineScope

internal object BadTestMarket {
    val memoryLruCache = ShareableLruCache(10)
    val db = FakeDb()

    private val memoryLruCacheStore = Store.Builder<String, Note, Note>()
        .read { throw Exception() }
        .write { _, _ -> throw Exception() }
        .delete { throw Exception() }
        .clear { throw Exception() }
        .build()

    private val dbStore = Store.Builder<String, Note, Note>()
        .read { throw Exception() }
        .write { _, _ -> throw Exception() }
        .delete { throw Exception() }
        .clear { throw Exception() }
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



