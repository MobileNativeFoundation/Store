package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * DSL-default source of truth that keeps the engine on one persistence path with or without a
 * caller-supplied implementation.
 *
 * Canonical-key cells are intentionally unbounded.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class InMemorySourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private class Cell<V : Any>(
        val row: V?,
        val version: Long,
    )

    private val lock = Mutex()
    private val cells = HashMap<KeyId, MutableStateFlow<Cell<V>>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            emitAll(cellFor(key).map { it.row })
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        update(key, value)
    }

    override suspend fun delete(key: K) {
        update(key, null)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        lock.withLock {
            cells.forEach { (keyId, cell) ->
                if (keyId.namespace == namespace.value) {
                    cell.emitNull()
                }
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            cells.values.forEach { cell -> cell.emitNull() }
        }
    }

    private suspend fun cellFor(key: K): MutableStateFlow<Cell<V>> {
        val keyId = KeyId.from(key)
        return lock.withLock { cellFor(keyId) }
    }

    private suspend fun update(
        key: K,
        row: V?,
    ) {
        val keyId = KeyId.from(key)
        lock.withLock {
            val cell = cellFor(keyId)
            val current = cell.value
            cell.value = Cell(row = row, version = current.version + 1L)
        }
    }

    private fun cellFor(keyId: KeyId): MutableStateFlow<Cell<V>> =
        cells.getOrPut(keyId) {
            MutableStateFlow(Cell(row = null, version = 0L))
        }

    private fun MutableStateFlow<Cell<V>>.emitNull() {
        val current = value
        value = Cell(row = null, version = current.version + 1L)
    }
}
