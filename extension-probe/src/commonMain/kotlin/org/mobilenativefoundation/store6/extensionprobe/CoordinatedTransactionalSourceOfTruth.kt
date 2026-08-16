package org.mobilenativefoundation.store6.extensionprobe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth

/**
 * Coordinates transactional acknowledgement across persistence, engine adoption, and projection.
 *
 * Install this wrapper as Store persistence and install [overlay] as the Store overlay. During
 * [acknowledge], every delegate reader collection has an isolated per-key gate and generation.
 * Journal retirement calls [signalRetired] while the transaction is still open; those attempts are
 * coalesced until the confirmed echo is adopted with [StoreWriteHandle.apply] and
 * [StoreWriteHandle.confirmFresh]. Each active collection is then restarted, whose contractually
 * immediate first row recaptures current authority while obsolete generations are ignored. The
 * latest row for every live collection is enqueued in the same critical section that reopens the
 * gate, and those rows are delivered before one retirement signal. Transaction rollback follows
 * the same authoritative recapture, discards signal attempts, and does not rely on `confirmFresh`
 * as an observation path.
 *
 * This is an unpublished buildability probe, not a general-purpose journal implementation.
 *
 * @param K the key type coordinated by this wrapper
 * @param V the non-null persisted and projected value type
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class CoordinatedTransactionalSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: TransactionalSourceOfTruth<K, V>,
    projection: Overlay<K, V>,
) : TransactionalSourceOfTruth<K, V> {
    private data class KeyIdentity(
        val namespace: String,
        val canonicalId: String,
    )

    private val gatesLock = Mutex()
    private val gates = HashMap<KeyIdentity, PerKeyGate<V>>()
    private val retirementSignals = MutableSharedFlow<K>()

    /** Overlay wrapper that forwards projection and emits only coordinated retirement signals. */
    public val overlay: Overlay<K, V> =
        CoordinatedOverlay(
            delegate = projection,
            retirementChanges = retirementSignals,
        )

    public override fun reader(key: K): Flow<V?> =
        flow {
            val gate = gateFor(key)
            val session = gate.registerReader()
            try {
                coroutineScope {
                    val delegateReader =
                        launch {
                            session.generation.collectLatest { generation ->
                                delegate.reader(key).collect { row ->
                                    gate.acceptDelegateRow(session, generation, row)
                                }
                            }
                        }

                    try {
                        for (delivery in session.deliveries) {
                            try {
                                emit(delivery.row)
                            } finally {
                                gate.finishDelivery(session, delivery.acknowledgement)
                            }
                        }
                    } finally {
                        delegateReader.cancel()
                    }
                }
            } finally {
                withContext(NonCancellable) { gate.unregisterReader(session) }
            }
        }

    public override suspend fun write(
        key: K,
        value: V,
    ) {
        delegate.write(key, value)
    }

    public override suspend fun delete(key: K) {
        delegate.delete(key)
    }

    public override suspend fun deleteNamespace(namespace: StoreNamespace) {
        delegate.deleteNamespace(namespace)
    }

    public override suspend fun deleteAll() {
        delegate.deleteAll()
    }

    public override suspend fun <R> withTransaction(block: suspend () -> R): R =
        delegate.withTransaction(block)

    /**
     * Commits [echo] and journal retirement, adopts the echo without fetching, then releases the
     * coordinated row and one queued retirement signal.
     *
     * [retire] runs inside the delegate transaction and must call [signalRetired] for each raw
     * retirement notification it would otherwise publish. Its journal storage must participate in
     * the delegate's transaction domain; this function type cannot enforce that relationship.
     * Multiple attempts for [key] coalesce.
     */
    public suspend fun acknowledge(
        key: K,
        echo: V,
        etag: String?,
        handle: StoreWriteHandle<K, V>,
        retire: suspend () -> Unit,
    ) {
        val gate = gateFor(key)
        gate.acknowledgementLock.lock()
        try {
            gate.close()
            try {
                delegate.withTransaction {
                    delegate.write(key, echo)
                    retire()
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    gate.recaptureAndOpen(releaseRetirementSignal = false)
                }
                throw failure
            }

            var adoptionCompleted = false
            try {
                handle.apply(key, echo)
                handle.confirmFresh(key, etag)
                adoptionCompleted = true
            } finally {
                withContext(NonCancellable) {
                    val signalRequested =
                        gate.recaptureAndOpen(releaseRetirementSignal = adoptionCompleted)
                    if (signalRequested) retirementSignals.emit(key)
                }
            }
        } finally {
            withContext(NonCancellable) { gate.forceOpen() }
            gate.acknowledgementLock.unlock()
        }
    }

    /**
     * Routes a raw journal-retirement signal through the per-key acknowledgement gate.
     *
     * Calls made while [acknowledge] is closed are coalesced and published after its row release;
     * calls made outside acknowledgement are forwarded immediately.
     */
    public suspend fun signalRetired(key: K) {
        val gate = gateFor(key)
        if (gate.requestRetirementSignal()) retirementSignals.emit(key)
    }

    /** Probe-only causal enrollment hook; retirement delivery never depends on this observation. */
    internal suspend fun awaitRetirementSignalSubscribers(expected: Int) {
        require(expected > 0) { "expected must be positive" }
        retirementSignals.subscriptionCount.first { count -> count >= expected }
    }

    private suspend fun gateFor(key: K): PerKeyGate<V> {
        val identity = KeyIdentity(key.namespace.value, key.canonicalId())
        return gatesLock.withLock { gates.getOrPut(identity) { PerKeyGate() } }
    }

    private class PerKeyGate<V : Any> {
        val acknowledgementLock = Mutex()

        private val stateLock = Mutex()
        private val readers = mutableSetOf<ReaderSession<V>>()
        private var closed = false
        private var retirementSignalRequested = false

        suspend fun close() {
            stateLock.withLock {
                closed = true
                retirementSignalRequested = false
                readers.forEach(ReaderSession<V>::clearCapture)
            }
        }

        suspend fun registerReader(): ReaderSession<V> =
            stateLock.withLock {
                ReaderSession<V>(startsClosed = closed).also(readers::add)
            }

        suspend fun unregisterReader(session: ReaderSession<V>) {
            stateLock.withLock {
                if (!readers.remove(session)) return
                session.active = false
                session.readiness?.complete(Unit)
                session.pendingAcknowledgements.forEach { acknowledgement ->
                    acknowledgement.complete(Unit)
                }
                session.pendingAcknowledgements.clear()
                session.deliveries.close()
            }
        }

        suspend fun acceptDelegateRow(
            session: ReaderSession<V>,
            generation: Long,
            row: V?,
        ) {
            stateLock.withLock {
                if (!session.active || generation != session.expectedGeneration) return
                if (closed) {
                    session.hasCapturedRow = true
                    session.capturedRow = row
                    session.readiness?.complete(Unit)
                } else {
                    session.deliveries.trySend(Delivery(row, acknowledgement = null))
                }
            }
        }

        suspend fun requestRetirementSignal(): Boolean =
            stateLock.withLock {
                if (closed) {
                    retirementSignalRequested = true
                    false
                } else {
                    true
                }
            }

        suspend fun recaptureAndOpen(releaseRetirementSignal: Boolean): Boolean {
            stateLock.withLock {
                readers.forEach { session ->
                    session.expectedGeneration += 1
                    session.clearCapture()
                    session.readiness = CompletableDeferred()
                    session.generation.value = session.expectedGeneration
                }
            }

            while (true) {
                val pendingReadiness =
                    stateLock.withLock {
                        readers.mapNotNull { session ->
                            session.readiness?.takeUnless { session.hasCapturedRow }
                        }
                    }
                pendingReadiness.forEach { it.await() }

                val release =
                    stateLock.withLock {
                        if (readers.any { !it.hasCapturedRow }) {
                            null
                        } else {
                            val acknowledgements =
                                readers.mapNotNull { session ->
                                    val acknowledgement = CompletableDeferred<Unit>()
                                    session.pendingAcknowledgements += acknowledgement
                                    val accepted =
                                        session.deliveries.trySend(
                                            Delivery(session.capturedRow, acknowledgement),
                                        )
                                    if (accepted.isSuccess) {
                                        acknowledgement
                                    } else {
                                        session.pendingAcknowledgements -= acknowledgement
                                        acknowledgement.complete(Unit)
                                        null
                                    }
                                }
                            val signalRequested =
                                releaseRetirementSignal && retirementSignalRequested
                            resetOpenState()
                            Release(acknowledgements, signalRequested)
                        }
                    }
                if (release != null) {
                    release.acknowledgements.forEach { it.await() }
                    return release.signalRequested
                }
            }
        }

        suspend fun forceOpen() {
            stateLock.withLock {
                readers.forEach { session -> session.readiness?.complete(Unit) }
                resetOpenState()
            }
        }

        suspend fun finishDelivery(
            session: ReaderSession<V>,
            acknowledgement: CompletableDeferred<Unit>?,
        ) {
            if (acknowledgement == null) return
            stateLock.withLock {
                session.pendingAcknowledgements -= acknowledgement
                acknowledgement.complete(Unit)
            }
        }

        private fun resetOpenState() {
            closed = false
            retirementSignalRequested = false
            readers.forEach(ReaderSession<V>::clearCapture)
        }
    }

    private class ReaderSession<V : Any>(startsClosed: Boolean) {
        val generation = MutableStateFlow(0L)
        val deliveries = Channel<Delivery<V>>(Channel.UNLIMITED)
        val pendingAcknowledgements = mutableSetOf<CompletableDeferred<Unit>>()
        var active = true
        var expectedGeneration = 0L
        var hasCapturedRow = false
        var capturedRow: V? = null
        var readiness: CompletableDeferred<Unit>? =
            if (startsClosed) CompletableDeferred() else null

        fun clearCapture() {
            hasCapturedRow = false
            capturedRow = null
            readiness = null
        }
    }

    private data class Delivery<V : Any>(
        val row: V?,
        val acknowledgement: CompletableDeferred<Unit>?,
    )

    private data class Release(
        val acknowledgements: List<CompletableDeferred<Unit>>,
        val signalRequested: Boolean,
    )

    @OptIn(DelicateStoreApi::class)
    private class CoordinatedOverlay<K : StoreKey, V : Any>(
        private val delegate: Overlay<K, V>,
        retirementChanges: Flow<StoreKey>,
    ) : Overlay<K, V> {
        override val changes: Flow<StoreKey> = merge(delegate.changes, retirementChanges)

        override fun apply(
            key: K,
            base: V?,
        ): V? = delegate.apply(key, base)
    }
}
