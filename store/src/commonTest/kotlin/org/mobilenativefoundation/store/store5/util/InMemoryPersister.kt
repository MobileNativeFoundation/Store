package org.mobilenativefoundation.store.store5.util

import org.mobilenativefoundation.store.store5.SourceOfTruth

/**
 * An in-memory non-flowing persister for testing.
 */
open class InMemoryPersister<Key : Any, Common : Any> {
    private val data = mutableMapOf<Key, Common>()
    var preWriteCallback: (suspend (key: Key, value: Common) -> Common)? = null
    var postReadCallback: (suspend (key: Key, value: Common?) -> Common?)? = null

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun read(key: Key): Common? {
        val value = data[key]
        postReadCallback?.let {
            return it(key, value)
        }
        return value
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    open suspend fun write(key: Key, output: Common) {
        val value = preWriteCallback?.invoke(key, output) ?: output
        data[key] = value
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteByKey(key: Key) {
        data.remove(key)
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteAll() {
        data.clear()
    }

    fun peekEntry(key: Key): Common? {
        return data[key]
    }
}

fun <Key : Any, Common : Any> InMemoryPersister<Key, Common>.asSourceOfTruth() =
    SourceOfTruth.of(
        nonFlowReader = ::read,
        writer = ::write,
        delete = ::deleteByKey,
        deleteAll = ::deleteAll
    )
