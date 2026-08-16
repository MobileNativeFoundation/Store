package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.WallClock

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class FakeWallClock(var now: Long) : WallClock {
    override fun nowEpochMillis(): Long = now
}

@OptIn(ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> storeWith(
    clock: WallClock? = null,
    bookkeeper: Bookkeeper? = null,
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> = store {
    clock?.let { wallClock(it) }
    bookkeeper?.let { this.bookkeeper(it) }
    configure()
}

/**
 * Public-seam-only counterpart to core's internal zero-config SoT for the borrowed compilation.
 * Per-key versioned cells preserve equal-value rewrites and repeated deletes without waking
 * unrelated readers.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> defaultConformanceSourceOfTruth(): SourceOfTruth<K, V> =
    BorrowedConformanceInMemorySourceOfTruth()

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
private class BorrowedConformanceInMemorySourceOfTruth<K : StoreKey, V : Any> :
    SourceOfTruth<K, V> {
    private class Cell<V : Any>(
        val row: V?,
        val version: Long,
    )

    private val lock = Mutex()
    private val cells = HashMap<ConformanceKey, MutableStateFlow<Cell<V>>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            emitAll(cellFor(key).map { cell -> cell.row })
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
            cells.forEach { (key, cell) ->
                if (key.namespace == namespace.value) cell.emitNull()
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            cells.values.forEach { cell -> cell.emitNull() }
        }
    }

    private suspend fun cellFor(key: K): MutableStateFlow<Cell<V>> {
        val identity = ConformanceKey.from(key)
        return lock.withLock { cellFor(identity) }
    }

    private suspend fun update(
        key: K,
        row: V?,
    ) {
        val identity = ConformanceKey.from(key)
        lock.withLock {
            val cell = cellFor(identity)
            val current = cell.value
            cell.value = Cell(row = row, version = current.version + 1L)
        }
    }

    private fun cellFor(key: ConformanceKey): MutableStateFlow<Cell<V>> =
        cells.getOrPut(key) {
            MutableStateFlow(Cell(row = null, version = 0L))
        }

    private fun MutableStateFlow<Cell<V>>.emitNull() {
        val current = value
        value = Cell(row = null, version = current.version + 1L)
    }
}

/**
 * Re-derivation of core's test-support shutdown for the borrowed compilation. This module cannot
 * reference core internals (CI guard + module visibility), so settle is close() only: engine
 * coroutines are cancelled but not joined. Cleanup-only; the borrowed scenarios' assertions never
 * depend on joined shutdown.
 */
internal suspend fun Store<*, *>.closeAndSettleForTest() {
    close()
}
