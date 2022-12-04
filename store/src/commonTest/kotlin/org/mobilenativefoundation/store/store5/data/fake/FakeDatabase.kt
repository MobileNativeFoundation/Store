@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.data.fake

import org.mobilenativefoundation.store.store5.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeDatabase<T : Any> : Store<String, T, T> {
    private val data: MutableMap<String, T> = mutableMapOf()
    private val writeRequests: MutableMap<String, Long?> = mutableMapOf()

    override fun read(key: String): Flow<T?> = flow {
        emit(data[key] as? T)
    }

    override suspend fun write(key: String, input: T): Boolean {
        data[key] = input
        return true
    }

    override suspend fun delete(key: String): Boolean {
        data.remove(key)
        return true
    }

    override suspend fun clear(): Boolean {
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

    fun deleteAllWriteRequests(): Boolean {
        this.writeRequests.clear()
        return true
    }

    fun reset() {
        data.clear()
        this.writeRequests.clear()
    }
}
