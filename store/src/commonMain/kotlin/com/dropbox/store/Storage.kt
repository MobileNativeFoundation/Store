package com.dropbox.store

import kotlinx.coroutines.flow.Flow

interface Storage<Key : Any> {
    fun <Output : Any> read(key: Key): Flow<Output?>
    fun <Input : Any> write(key: Key, input: Input): Boolean
    fun delete(key: Key): Boolean
    fun clear(): Boolean
}