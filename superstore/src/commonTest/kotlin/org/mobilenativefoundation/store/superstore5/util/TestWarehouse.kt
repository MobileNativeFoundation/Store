package org.mobilenativefoundation.store.superstore5.util

import org.mobilenativefoundation.store.superstore5.Warehouse

interface TestWarehouse<Key : Any, Output : Any> : Warehouse<Key, Output> {
    suspend fun get(key: String, fail: Boolean = false, ttl: Long? = null): Output?
}
