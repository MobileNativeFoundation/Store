package com.dropbox.android.external.store4.testutil

/**
 * An in-memory non-flowing persister for testing.
 */
class InMemoryPersister<Key, Output> {
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
