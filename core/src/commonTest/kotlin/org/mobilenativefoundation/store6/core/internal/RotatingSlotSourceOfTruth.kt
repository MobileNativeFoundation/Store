package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * Fault fixture that intentionally violates the source-of-truth reader-liveness contract.
 *
 * Per-key, namespace, and all destructive deletes rotate matching entries to new null-seeded slots
 * without notifying collectors of the old slots. This is reserved for later engine recovery tests
 * and must never receive a `SourceOfTruthContractKit` runner.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class RotatingSlotSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private class Slot<V : Any>(
        val rows: MutableSharedFlow<V?>,
        val firstReaderDelivery: CompletableDeferred<Unit>,
    )

    private class Entry<V : Any>(
        var slot: Slot<V>,
        var subscriptionCount: Int,
    )

    private val lock = Mutex()
    private val entries = HashMap<KeyId, Entry<V>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            val captured = captureSlot(key)
            captured.rows.collect { value ->
                emit(value)
                captured.firstReaderDelivery.complete(Unit)
            }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        val keyId = KeyId.from(key)
        lock.withLock {
            check(entryFor(keyId).slot.rows.tryEmit(value))
        }
    }

    override suspend fun delete(key: K) {
        val keyId = KeyId.from(key)
        lock.withLock {
            entryFor(keyId).slot = newSlot()
        }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        lock.withLock {
            entries.forEach { (keyId, entry) ->
                if (keyId.namespace == namespace.value) {
                    entry.slot = newSlot()
                }
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            entries.values.forEach { entry -> entry.slot = newSlot() }
        }
    }

    internal suspend fun subscriptionCount(key: K): Int {
        val keyId = KeyId.from(key)
        return lock.withLock { entries[keyId]?.subscriptionCount ?: 0 }
    }

    /** Waits until the current slot's first row has crossed downstream raw-reader capture. */
    internal suspend fun awaitCurrentSlotReaderDelivery(key: K) {
        val keyId = KeyId.from(key)
        val delivery = lock.withLock { entryFor(keyId).slot.firstReaderDelivery }
        delivery.await()
    }

    private suspend fun captureSlot(key: K): Slot<V> {
        val keyId = KeyId.from(key)
        return lock.withLock {
            entryFor(keyId).also { entry ->
                entry.subscriptionCount += 1
            }.slot
        }
    }

    private fun entryFor(keyId: KeyId): Entry<V> =
        entries.getOrPut(keyId) {
            Entry(slot = newSlot(), subscriptionCount = 0)
        }

    private fun newSlot(): Slot<V> {
        val rows =
            MutableSharedFlow<V?>(
                replay = 1,
                extraBufferCapacity = 64,
            )
        check(rows.tryEmit(null))
        return Slot(
            rows = rows,
            firstReaderDelivery = CompletableDeferred(),
        )
    }
}
