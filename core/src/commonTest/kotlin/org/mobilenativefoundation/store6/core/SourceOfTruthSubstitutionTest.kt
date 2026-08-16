@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.WallClock

/** Installs an alternate source of truth without changing public Store conformance scenarios. */
abstract class SourceOfTruthSubstitutionTest {
    private var currentReaderProbe: ReaderDeliveryProbeSourceOfTruth<*, *>? = null

    protected open fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(
            trackedSot(defaultConformanceSourceOfTruth()),
        )
    }

    /** Wraps a test SoT so scheduler-sensitive scenarios can await a real downstream delivery. */
    protected fun <K : StoreKey, V : Any> trackedSot(
        delegate: SourceOfTruth<K, V>,
    ): SourceOfTruth<K, V> =
        ReaderDeliveryProbeSourceOfTruth(delegate).also { currentReaderProbe = it }

    /**
     * Records the latest started reader collection before a direct key clear. The matching await
     * must then observe a later collection's completed engine-facing delivery.
     */
    protected suspend fun prepareNextReaderDelivery(key: StoreKey) {
        checkNotNull(currentReaderProbe) {
            "The substitution must install its SourceOfTruth through trackedSot()."
        }.prepareNextReaderDelivery(key)
    }

    /**
     * Test-fixture acknowledgement completed only after the selected reader value returns from the
     * engine-facing emit. The public LocalOnly baseline starts demand; this closes the downstream
     * raw-stamping edge before a fetch or destructive mutation is released.
     */
    protected open suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        checkNotNull(currentReaderProbe) {
            "The substitution must install its SourceOfTruth through trackedSot()."
        }.awaitCurrentReaderFirstDelivery(key)
    }

    protected fun <K : StoreKey, V : Any> testStore(
        configure: StoreBuilder<K, V>.() -> Unit,
    ): Store<K, V> =
        store {
            configure()
            installSot(this)
        }

    /** Preserves injected test seams while routing the Store through the same substitution hook. */
    internal fun <K : StoreKey, V : Any> testStoreWith(
        clock: WallClock? = null,
        bookkeeper: Bookkeeper? = null,
        configure: StoreBuilder<K, V>.() -> Unit,
    ): Store<K, V> =
        storeWith(clock = clock, bookkeeper = bookkeeper) {
            configure()
            installSot(this)
    }
}

/** No-yield acknowledgement decorator; adversarial decorators may remain nested inside it. */
internal class ReaderDeliveryProbeSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: SourceOfTruth<K, V>,
) : SourceOfTruth<K, V> {
    private val deliveries = ConformanceReaderDeliveries()

    override fun reader(key: K): Flow<V?> =
        flow {
            val sequence = deliveries.begin(key)
            delegate.reader(key).collect { value ->
                emit(value)
                deliveries.complete(key, sequence)
            }
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        delegate.write(key, value)
    }

    override suspend fun delete(key: K) {
        delegate.delete(key)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        delegate.deleteNamespace(namespace)
    }

    override suspend fun deleteAll() {
        delegate.deleteAll()
    }

    suspend fun prepareNextReaderDelivery(key: StoreKey) {
        deliveries.prepareNext(key)
    }

    suspend fun awaitCurrentReaderFirstDelivery(key: StoreKey) {
        // Callers own cancellation through their suite-level runTest bound.
        withContext(Dispatchers.Default) {
            deliveries.awaitCurrent(key)
        }
    }
}

private class ConformanceReaderDeliveries {
    private val lock = Mutex()
    private val current = HashMap<ConformanceKey, ConformanceReaderDeliveryState>()
    private val preparedFloors = HashMap<ConformanceKey, Long>()

    suspend fun begin(key: StoreKey): Long =
        lock.withLock {
            val deliveries = deliveriesFor(key)
            deliveries.startedSequence += 1L
            deliveries.startedSequence
        }

    suspend fun complete(
        key: StoreKey,
        sequence: Long,
    ) {
        lock.withLock {
            val deliveries = deliveriesFor(key)
            if (sequence > deliveries.completedSequence.value) {
                deliveries.completedSequence.value = sequence
            }
        }
    }

    suspend fun prepareNext(key: StoreKey) {
        lock.withLock {
            val identity = ConformanceKey.from(key)
            preparedFloors[identity] = deliveriesFor(identity).startedSequence
        }
    }

    suspend fun awaitCurrent(key: StoreKey) {
        val (completedSequence, floor) =
            lock.withLock {
                val identity = ConformanceKey.from(key)
                deliveriesFor(identity).completedSequence to
                    (preparedFloors.remove(identity) ?: 0L)
            }
        completedSequence.first { sequence -> sequence > floor }
    }

    private fun deliveriesFor(key: StoreKey): ConformanceReaderDeliveryState =
        deliveriesFor(ConformanceKey.from(key))

    private fun deliveriesFor(key: ConformanceKey): ConformanceReaderDeliveryState =
        current.getOrPut(key) { ConformanceReaderDeliveryState() }
}

private class ConformanceReaderDeliveryState(
    var startedSequence: Long = 0L,
    val completedSequence: MutableStateFlow<Long> = MutableStateFlow(0L),
)

internal data class ConformanceKey(
    val namespace: String,
    val canonicalId: String,
) {
    companion object {
        fun from(key: StoreKey): ConformanceKey =
            ConformanceKey(
                namespace = key.namespace.value,
                canonicalId = key.canonicalId(),
            )
    }
}
