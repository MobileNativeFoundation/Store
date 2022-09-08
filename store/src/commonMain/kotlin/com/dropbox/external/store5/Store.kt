package com.dropbox.external.store5

import kotlinx.coroutines.flow.Flow

typealias Read<Key, Output> = suspend (key: Key) -> Flow<Output?>
typealias Write<Key, Input> = suspend (key: Key, input: Input) -> Boolean
typealias Delete<Key> = suspend (key: Key) -> Boolean
typealias DeleteAll = suspend () -> Boolean

/**
 * Interacts with a data source.
 * We recommend [Store] bind to one [Persister]. However, [Store] can bind any source(s) of data.
 * A [Market] implementation requires at least one [Store]. But typical applications have at least two: one bound to a memory cache and another bound to a database.
 * @see [Persister].
 * @see [Market].
 */
data class Store<Key, Input, Output>(
    val read: Read<Key, Output>,
    val write: Write<Key, Input>,
    val delete: Delete<Key>,
    val deleteAll: DeleteAll
)