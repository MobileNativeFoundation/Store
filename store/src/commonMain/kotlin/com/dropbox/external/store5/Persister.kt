package com.dropbox.external.store5

import kotlinx.coroutines.flow.Flow

/**
 * Represents a source of persistent data.
 * Provided for convenience. A [Store] implementation can bind the implementation of a [Persister].
 * @see [Store]
 * @examples Memory cache, disk cache, database, shared preferences, local storage.
 */
interface Persister<Key : Any> {
    fun <Output : Any> read(key: Key): Flow<Output?>
    fun <Input : Any> write(key: Key, input: Input): Boolean
    fun delete(key: Key): Boolean
    fun delete(): Boolean
}