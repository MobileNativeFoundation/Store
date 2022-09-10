@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.fake

import com.dropbox.external.store5.Persister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeDb : Persister<String> {
    private val data: MutableMap<String, Any> = mutableMapOf()
    private val writeRequests: MutableMap<String, Long?> = mutableMapOf()

    override fun <Output : Any> read(key: String): Flow<Output?> = flow {
        emit(data[key] as? Output)
    }

    override suspend fun <Input : Any> write(key: String, input: Input): Boolean {
        data[key] = input
        return true
    }

    override suspend fun delete(key: String): Boolean {
        data.remove(key)
        return true
    }

    override suspend fun delete(): Boolean {
        data.clear()
        return true
    }

    fun setLastWriteTime(key: String, updated: Long?): Boolean {
        this.writeRequests[key] = updated
        return true
    }

    fun getLastWriteTime(key: String): Long? = this.writeRequests[key]

    fun deleteWriteRequest(key: String): Boolean {
        this.writeRequests.remove(key)
        return true
    }

    fun reset() {
        data.clear()
        this.writeRequests.clear()
    }
}