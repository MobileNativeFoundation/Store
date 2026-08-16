package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/** Reusable SharedFlow-backed source-of-truth fake exercised by the full contract kit. */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class SharedFlowSourceOfTruth<K : StoreKey, V : Any>(
    private val beforeBulkEmission: suspend () -> Unit = {},
) : SourceOfTruth<K, V> {
    private val slotsLock = Mutex()
    private val mutationLock = Mutex()
    private val slots = HashMap<KeyId, MutableSharedFlow<V?>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            emitAll(slotFor(key))
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
        emitNullToSlots { keyId -> keyId.namespace == namespace.value }
    }

    override suspend fun deleteAll() {
        emitNullToSlots { true }
    }

    private suspend fun update(
        key: K,
        row: V?,
    ) {
        currentCoroutineContext().ensureActive()
        mutationLock.withLock {
            withContext(NonCancellable) {
                slotFor(key).emit(row)
            }
        }
    }

    private suspend fun slotFor(key: K): MutableSharedFlow<V?> {
        val keyId = KeyId.from(key)
        return slotsLock.withLock {
            slots.getOrPut(keyId) { newSlot() }
        }
    }

    private suspend fun emitNullToSlots(matches: (KeyId) -> Boolean) {
        currentCoroutineContext().ensureActive()
        mutationLock.withLock {
            withContext(NonCancellable) {
                val matchingSlots = slotsLock.withLock { slots.filterKeys(matches).toList() }
                beforeBulkEmission()
                matchingSlots.forEach { (_, slot) -> slot.emit(null) }
            }
        }
    }

    private fun newSlot(): MutableSharedFlow<V?> =
        MutableSharedFlow<V?>(
            replay = 1,
            extraBufferCapacity = 64,
        ).also { slot ->
            check(slot.tryEmit(null))
        }
}
