package com.dropbox.android.external.store4.testutil

import com.dropbox.android.external.store4.SourceOfTruth

/**
 * An in-memory non-flowing persister for testing.
 */
class InMemoryPersister<Key : Any, Output : Any> {
    private val data = mutableMapOf<Key, Output>()

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun read(key: Key) = data[key]

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun write(key: Key, output: Output) {
        data[key] = output
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteByKey(key: Key) {
        data.remove(key)
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteAll() {
        data.clear()
    }

    fun peekEntry(key: Key): Output? {
        return data[key]
    }
}

fun <Key : Any, Output : Any> InMemoryPersister<Key, Output>.asSourceOfTruth() =
    SourceOfTruth.of(
        nonFlowReader = ::read,
        writer = ::write,
        delete = ::deleteByKey,
        deleteAll = ::deleteAll
    )
