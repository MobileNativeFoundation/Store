package org.mobilenativefoundation.store6.extensionprobe

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private data class CoordinatorKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("coordinator")

    override fun canonicalId(): String = id
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private class NoisyTransactionalSourceOfTruth(
    initial: String,
) : TransactionalSourceOfTruth<CoordinatorKey, String> {
    private data class RowEvent(
        val row: String?,
        val acknowledgements: Channel<Unit>?,
    )

    private val rows = MutableSharedFlow<RowEvent>(replay = 1)
    private val readerStarts = Channel<Unit>(Channel.UNLIMITED)
    private val readerRowsReleased = CompletableDeferred<Unit>()
    private val oldIntermediate = initial
    private var current: String = initial
    private val committedPendingMutation = MutableStateFlow(true)
    private var transactionPendingMutation: Boolean? = null

    val pendingMutation: Boolean
        get() = committedPendingMutation.value
    var writeCalls: Int = 0
        private set
    var retirementCalls: Int = 0
        private set
    val rawWriteNotifications: MutableList<String?> = mutableListOf()

    init {
        check(rows.tryEmit(RowEvent(initial, acknowledgements = null)))
    }

    override fun reader(key: CoordinatorKey): Flow<String?> =
        flow {
            readerStarts.send(Unit)
            readerRowsReleased.await()
            rows.collect { event ->
                emit(event.row)
                event.acknowledgements?.send(Unit)
            }
        }

    override suspend fun write(
        key: CoordinatorKey,
        value: String,
    ) {
        writeCalls++
        publish(oldIntermediate, recordAsWriteNotification = true)
        publish(null, recordAsWriteNotification = true)
        current = value
        publish(value, recordAsWriteNotification = true)
    }

    override suspend fun delete(key: CoordinatorKey) {
        error("not used by the probe")
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        error("not used by the probe")
    }

    override suspend fun deleteAll() {
        error("not used by the probe")
    }

    override suspend fun <R> withTransaction(block: suspend () -> R): R {
        val rowBefore = current
        check(transactionPendingMutation == null) { "nested transactions are not supported" }
        transactionPendingMutation = committedPendingMutation.value
        try {
            val result = block()
            committedPendingMutation.value = checkNotNull(transactionPendingMutation)
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                current = rowBefore
                publish(current, recordAsWriteNotification = false)
            }
            throw failure
        } finally {
            transactionPendingMutation = null
        }
    }

    suspend fun retireMutation() {
        retirementCalls++
        checkNotNull(transactionPendingMutation) { "retirement must run inside a transaction" }
        transactionPendingMutation = false
    }

    suspend fun publishAuthoritative(value: String) {
        current = value
        publish(value, recordAsWriteNotification = false)
    }

    fun resetWriteObservations() {
        writeCalls = 0
        rawWriteNotifications.clear()
    }

    suspend fun awaitReaderStarts(expected: Int) {
        require(expected > 0) { "expected must be positive" }
        repeat(expected) { readerStarts.receive() }
    }

    fun releaseReaderRows() {
        check(readerRowsReleased.complete(Unit)) { "reader rows were already released" }
    }

    private suspend fun publish(
        row: String?,
        recordAsWriteNotification: Boolean,
    ) {
        if (recordAsWriteNotification) rawWriteNotifications += row
        val expectedAcknowledgements = rows.subscriptionCount.value
        val acknowledgements = Channel<Unit>(Channel.UNLIMITED)
        rows.emit(RowEvent(row, acknowledgements))
        repeat(expectedAcknowledgements) { acknowledgements.receive() }
    }
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private class PendingMutationOverlay(
    private val source: NoisyTransactionalSourceOfTruth,
) : Overlay<CoordinatorKey, String> {
    private val completedApplications = MutableStateFlow(0L)

    val completedApplicationCount: Long
        get() = completedApplications.value

    suspend fun awaitApplicationAfter(observed: Long) {
        completedApplications.first { it > observed }
    }

    override fun apply(
        key: CoordinatorKey,
        base: String?,
    ): String? {
        val output =
            if (source.pendingMutation) {
                base?.let { "$it+pending" } ?: "pending"
            } else {
                base
            }
        completedApplications.update { it + 1L }
        return output
    }

    override val changes: Flow<StoreKey> = emptyFlow()
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private object PendingFreeOverlay : Overlay<CoordinatorKey, String> {
    override fun apply(
        key: CoordinatorKey,
        base: String?,
    ): String? = base

    override val changes: Flow<StoreKey> = emptyFlow()
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private object NoOpWriteHandle : StoreWriteHandle<CoordinatorKey, String> {
    override suspend fun apply(
        key: CoordinatorKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: CoordinatorKey) = Unit

    override suspend fun confirmFresh(
        key: CoordinatorKey,
        etag: String?,
    ) = Unit
}

private enum class AdoptionFailurePoint {
    APPLY,
    CONFIRM,
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private class SkewedTransactionalSourceOfTruth(
    initial: String,
) : TransactionalSourceOfTruth<CoordinatorKey, String> {
    private data class Reader(
        val id: Int,
        val rows: Channel<String?> = Channel(Channel.UNLIMITED),
    )

    private val stateLock = Mutex()
    private val readers = mutableListOf<Reader>()
    private var current = initial
    private var nextReaderId = 0
    private val slowReaderId = 1
    private val slowReaderOldRowObserved = CompletableDeferred<Unit>()
    private val fastReaderEchoObserved = CompletableDeferred<Unit>()
    private val holdSlowReaderRemainder = CompletableDeferred<Unit>()

    override fun reader(key: CoordinatorKey): Flow<String?> =
        flow {
            val (reader, firstRow) =
                stateLock.withLock {
                    Reader(nextReaderId++).also(readers::add) to current
                }
            try {
                emit(firstRow)
                for (row in reader.rows) {
                    emit(row)
                    when {
                        reader.id == slowReaderId && row == "base" -> {
                            slowReaderOldRowObserved.complete(Unit)
                            holdSlowReaderRemainder.await()
                        }

                        reader.id != slowReaderId && row == "echo" ->
                            fastReaderEchoObserved.complete(Unit)
                    }
                }
            } finally {
                stateLock.withLock { readers.remove(reader) }
            }
        }

    override suspend fun write(
        key: CoordinatorKey,
        value: String,
    ) {
        val activeReaders =
            stateLock.withLock {
                current = value
                readers.toList()
            }
        activeReaders.forEach { reader ->
            check(reader.rows.trySend("base").isSuccess)
            check(reader.rows.trySend(null).isSuccess)
            check(reader.rows.trySend(value).isSuccess)
        }
    }

    override suspend fun delete(key: CoordinatorKey) = error("not used by the regression")

    override suspend fun deleteNamespace(namespace: StoreNamespace) =
        error("not used by the regression")

    override suspend fun deleteAll() = error("not used by the regression")

    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()

    suspend fun awaitSkew() {
        fastReaderEchoObserved.await()
        slowReaderOldRowObserved.await()
    }

    fun releaseSlowReader() {
        holdSlowReaderRemainder.complete(Unit)
    }
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private class BufferedReaderTransactionalSourceOfTruth(
    initial: String,
) : TransactionalSourceOfTruth<CoordinatorKey, String> {
    private data class Reader(
        val id: Int,
        val rows: Channel<String?> = Channel(Channel.UNLIMITED),
    )

    private val stateLock = Mutex()
    private val readers = mutableListOf<Reader>()
    private val readerRegistrations = Channel<Int>(Channel.UNLIMITED)
    private var current = initial
    private var nextReaderId = 0

    override fun reader(key: CoordinatorKey): Flow<String?> =
        flow {
            val (reader, firstRow) =
                stateLock.withLock {
                    Reader(nextReaderId++).also(readers::add) to current
                }
            try {
                check(readerRegistrations.trySend(reader.id).isSuccess)
                emit(firstRow)
                for (row in reader.rows) emit(row)
            } finally {
                stateLock.withLock { readers.remove(reader) }
            }
        }

    override suspend fun write(
        key: CoordinatorKey,
        value: String,
    ) {
        val activeReaders =
            stateLock.withLock {
                current = value
                readers.toList()
            }
        activeReaders.forEach { reader -> check(reader.rows.trySend(value).isSuccess) }
    }

    override suspend fun delete(key: CoordinatorKey) = error("not used by the regression")

    override suspend fun deleteNamespace(namespace: StoreNamespace) =
        error("not used by the regression")

    override suspend fun deleteAll() = error("not used by the regression")

    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()

    suspend fun publishToReader(
        readerId: Int,
        values: List<String>,
    ) {
        val reader = stateLock.withLock { readers.single { it.id == readerId } }
        values.forEach { value -> check(reader.rows.trySend(value).isSuccess) }
    }

    suspend fun publishAuthoritative(value: String) {
        val activeReaders =
            stateLock.withLock {
                current = value
                readers.toList()
            }
        activeReaders.forEach { reader -> check(reader.rows.trySend(value).isSuccess) }
    }

    suspend fun awaitReaderRegistration(readerId: Int) {
        while (readerRegistrations.receive() != readerId) {
            // Fresh generations receive monotonically increasing ids; skip earlier registrations.
        }
    }
}

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class CoordinatedTransactionalSourceOfTruthTest {
    @Test
    fun retirementVisibility_isPrivateUntilCommit_andRollbackKeepsMutationPending() = runTest {
        val delegate = NoisyTransactionalSourceOfTruth(initial = "base")
        val retirementStaged = CompletableDeferred<Unit>()
        val holdTransaction = CompletableDeferred<Unit>()
        val transaction = backgroundScope.launch {
            delegate.withTransaction {
                delegate.retireMutation()
                retirementStaged.complete(Unit)
                holdTransaction.await()
            }
        }

        retirementStaged.await()
        assertTrue(delegate.pendingMutation)
        transaction.cancel(CancellationException("roll back staged retirement"))
        transaction.join()
        assertTrue(delegate.pendingMutation)

        delegate.withTransaction {
            delegate.retireMutation()
            assertTrue(delegate.pendingMutation)
        }
        assertFalse(delegate.pendingMutation)
    }

    @Test
    fun applyFailure_rethrowsAndReopensWithAuthority_withoutRetirementSignal() = runTest {
        val failure =
            exerciseFailedAdoption(
                failurePoint = AdoptionFailurePoint.APPLY,
                failure = IllegalStateException("apply failed"),
            )

        assertEquals("apply failed", assertIs<IllegalStateException>(failure).message)
    }

    @Test
    fun confirmFailure_rethrowsAndReopensWithAuthority_withoutRetirementSignal() = runTest {
        val failure =
            exerciseFailedAdoption(
                failurePoint = AdoptionFailurePoint.CONFIRM,
                failure = IllegalStateException("confirm failed"),
            )

        assertEquals("confirm failed", assertIs<IllegalStateException>(failure).message)
    }

    @Test
    fun confirmCancellation_cancelsCallerAndReopensWithAuthority_withoutRetirementSignal() = runTest {
        val key = CoordinatorKey("confirm-cancelled")
        val delegate = BufferedReaderTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val rows = Channel<String?>(Channel.UNLIMITED)
        val reader = backgroundScope.launch {
            coordinated.reader(key).collect(rows::send)
        }
        assertEquals("base", rows.receive())
        val signals = Channel<StoreKey>(Channel.UNLIMITED)
        val signalReader = backgroundScope.launch {
            coordinated.overlay.changes.collect(signals::send)
        }
        coordinated.awaitRetirementSignalSubscribers(expected = 1)
        val confirmReached = CompletableDeferred<Unit>()
        val holdConfirm = CompletableDeferred<Unit>()
        val acknowledgement = backgroundScope.launch {
            coordinated.acknowledge(
                key = key,
                echo = "echo",
                etag = null,
                handle =
                    object : StoreWriteHandle<CoordinatorKey, String> {
                        override suspend fun apply(
                            key: CoordinatorKey,
                            value: String,
                        ) = Unit

                        override suspend fun markStale(key: CoordinatorKey) = Unit

                        override suspend fun confirmFresh(
                            key: CoordinatorKey,
                            etag: String?,
                        ) {
                            confirmReached.complete(Unit)
                            holdConfirm.await()
                        }
                    },
                retire = {
                    coordinated.signalRetired(key)
                    coordinated.signalRetired(key)
                },
            )
        }
        confirmReached.await()
        acknowledgement.cancel(CancellationException("confirm cancelled"))
        acknowledgement.join()

        assertTrue(acknowledgement.isCancelled)
        assertEquals("echo", rows.receive())
        testScheduler.runCurrent()
        assertTrue(signals.tryReceive().isFailure)

        delegate.publishAuthoritative("later")
        assertEquals("later", rows.receive())
        assertTrue(signals.tryReceive().isFailure)
        reader.cancel()
        signalReader.cancel()
    }

    @Test
    fun retirementSignal_waitsForDownstreamAuthoritativeRowDelivery() = runTest {
        val key = CoordinatorKey("delivery-order")
        val delegate = BufferedReaderTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val rows = Channel<String?>(Channel.UNLIMITED)
        val echoCallbackReached = CompletableDeferred<Unit>()
        val allowEchoCallbackToReturn = CompletableDeferred<Unit>()
        val reader = backgroundScope.launch {
            coordinated.reader(key).collect { row ->
                if (row == "echo") {
                    echoCallbackReached.complete(Unit)
                    allowEchoCallbackToReturn.await()
                }
                rows.send(row)
            }
        }
        assertEquals("base", rows.receive())
        val signals = Channel<StoreKey>(Channel.UNLIMITED)
        val signalReader = backgroundScope.launch {
            coordinated.overlay.changes.collect(signals::send)
        }
        coordinated.awaitRetirementSignalSubscribers(expected = 1)
        val acknowledgement = backgroundScope.launch {
            coordinated.acknowledge(
                key = key,
                echo = "echo",
                etag = null,
                handle = NoOpWriteHandle,
                retire = {
                    coordinated.signalRetired(key)
                    coordinated.signalRetired(key)
                },
            )
        }

        echoCallbackReached.await()
        testScheduler.runCurrent()
        try {
            assertFalse(acknowledgement.isCompleted)
            assertTrue(signals.tryReceive().isFailure)
        } finally {
            allowEchoCallbackToReturn.complete(Unit)
        }

        assertEquals("echo", rows.receive())
        withTimeout(1.seconds) { acknowledgement.join() }
        assertEquals(key, signals.receive())
        assertTrue(signals.tryReceive().isFailure)
        reader.cancel()
        signalReader.cancel()
    }

    @Test
    fun skewedConcurrentReaders_recaptureTheirOwnAuthoritativeEcho() = runTest {
        val key = CoordinatorKey("skew")
        val delegate = SkewedTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val firstRows = Channel<String?>(Channel.UNLIMITED)
        val secondRows = Channel<String?>(Channel.UNLIMITED)
        val firstReader = backgroundScope.launch {
            coordinated.reader(key).collect(firstRows::send)
        }
        assertEquals("base", firstRows.receive())
        val secondReader = backgroundScope.launch {
            coordinated.reader(key).collect(secondRows::send)
        }
        assertEquals("base", secondRows.receive())

        try {
            coordinated.acknowledge(
                key = key,
                echo = "echo",
                etag = null,
                handle =
                    object : StoreWriteHandle<CoordinatorKey, String> {
                        override suspend fun apply(
                            key: CoordinatorKey,
                            value: String,
                        ) = Unit

                        override suspend fun markStale(key: CoordinatorKey) = Unit

                        override suspend fun confirmFresh(
                            key: CoordinatorKey,
                            etag: String?,
                        ) {
                            delegate.awaitSkew()
                        }
                    },
                retire = {},
            )

            assertEquals("echo", firstRows.receive())
            assertEquals("echo", secondRows.receive())
        } finally {
            delegate.releaseSlowReader()
            firstReader.cancel()
            secondReader.cancel()
        }
    }

    @Test
    fun readerCancellationDuringRelease_doesNotStrandAcknowledgementOrGate() = runTest {
        val key = CoordinatorKey("cancel-release")
        val delegate = BufferedReaderTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val healthyRows = Channel<String?>(Channel.UNLIMITED)
        val healthyReader = backgroundScope.launch {
            coordinated.reader(key).collect(healthyRows::send)
        }
        assertEquals("base", healthyRows.receive())
        val holdSlowReader = CompletableDeferred<Unit>()
        val slowReaderStarted = CompletableDeferred<Unit>()
        val slowReader = backgroundScope.launch {
            coordinated.reader(key).collect {
                slowReaderStarted.complete(Unit)
                holdSlowReader.await()
            }
        }
        slowReaderStarted.await()
        delegate.publishToReader(readerId = 1, values = List(100) { "queued-$it" })
        testScheduler.runCurrent()

        val acknowledgement = backgroundScope.launch {
            coordinated.acknowledge(
                key = key,
                echo = "echo",
                etag = null,
                handle = NoOpWriteHandle,
                retire = {},
            )
        }
        assertEquals("echo", healthyRows.receive())
        slowReader.cancel()

        withTimeout(1.seconds) { acknowledgement.join() }
        assertTrue(acknowledgement.isCompleted)

        val laterRows = Channel<String?>(Channel.UNLIMITED)
        val laterReader = backgroundScope.launch {
            coordinated.reader(key).collect(laterRows::send)
        }
        assertEquals("echo", laterRows.receive())
        laterReader.cancel()
        healthyReader.cancel()
    }

    @Test
    fun closedGateJoiner_andReleaseBoundaryRow_areDeliveredInAuthoritativeOrder() = runTest {
        val key = CoordinatorKey("closed-joiner")
        val delegate = BufferedReaderTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val originalRows = Channel<String?>(Channel.UNLIMITED)
        val originalReader = backgroundScope.launch {
            coordinated.reader(key).collect(originalRows::send)
        }
        assertEquals("base", originalRows.receive())
        delegate.awaitReaderRegistration(readerId = 0)
        val holdSlowReader = CompletableDeferred<Unit>()
        val slowReaderStarted = CompletableDeferred<Unit>()
        val slowReader = backgroundScope.launch {
            coordinated.reader(key).collect {
                slowReaderStarted.complete(Unit)
                holdSlowReader.await()
            }
        }
        slowReaderStarted.await()
        delegate.awaitReaderRegistration(readerId = 1)
        delegate.publishToReader(readerId = 1, values = List(100) { "queued-$it" })
        testScheduler.runCurrent()
        val confirmReached = CompletableDeferred<Unit>()
        val allowConfirm = CompletableDeferred<Unit>()
        val acknowledgement = backgroundScope.launch {
            coordinated.acknowledge(
                key = key,
                echo = "echo",
                etag = null,
                handle =
                    object : StoreWriteHandle<CoordinatorKey, String> {
                        override suspend fun apply(
                            key: CoordinatorKey,
                            value: String,
                        ) = Unit

                        override suspend fun markStale(key: CoordinatorKey) = Unit

                        override suspend fun confirmFresh(
                            key: CoordinatorKey,
                            etag: String?,
                        ) {
                            confirmReached.complete(Unit)
                            allowConfirm.await()
                        }
                    },
                retire = {},
            )
        }
        confirmReached.await()

        val joiningRows = Channel<String?>(Channel.UNLIMITED)
        val joiningReader = backgroundScope.launch {
            coordinated.reader(key).collect(joiningRows::send)
        }
        delegate.awaitReaderRegistration(readerId = 2)
        testScheduler.runCurrent()
        assertTrue(joiningRows.tryReceive().isFailure)

        allowConfirm.complete(Unit)
        assertEquals("echo", originalRows.receive())
        assertEquals("echo", joiningRows.receive())
        assertFalse(acknowledgement.isCompleted)

        delegate.publishAuthoritative("later")
        testScheduler.runCurrent()

        assertEquals("later", originalRows.receive())
        assertEquals("later", joiningRows.receive())
        assertFalse(acknowledgement.isCompleted)

        holdSlowReader.complete(Unit)
        withTimeout(1.seconds) { acknowledgement.join() }
        assertTrue(acknowledgement.isCompleted)
        originalReader.cancel()
        joiningReader.cancel()
        slowReader.cancel()
    }

    @Test
    fun acknowledge_gatesBothWrites_thenReleasesLatestRowBeforeOneRetirementSignal() = runTest {
        val key = CoordinatorKey("1")
        val delegate = NoisyTransactionalSourceOfTruth(initial = "base")
        val overlay = PendingMutationOverlay(delegate)
        val coordinated = CoordinatedTransactionalSourceOfTruth(delegate, overlay)
        var fetches = 0
        val store = store<CoordinatorKey, String> {
            fetcher {
                fetches++
                "network"
            }
            persistence(coordinated)
            overlay(coordinated.overlay)
        }

        try {
            val realHandle = checkNotNull(store.runtime()).writeHandle
            realHandle.apply(key, "base")
            realHandle.confirmFresh(key, etag = "seed")
            val localValues = Channel<String>(Channel.UNLIMITED)
            val localCollector = backgroundScope.launch {
                store.stream(key, Freshness.LocalOnly).collect { result ->
                    if (result is StoreResult.Data<String>) localValues.send(result.value)
                }
            }
            assertEquals("base+pending", localValues.receive())
            delegate.awaitReaderStarts(expected = 1)
            val beforeReplay = overlay.completedApplicationCount
            delegate.releaseReaderRows()
            overlay.awaitApplicationAfter(beforeReplay)
            delegate.resetWriteObservations()
            val fetchesBeforeAcknowledge = fetches

            store.stream(key, Freshness.CachedOrFetch).test {
                assertEquals("base+pending", awaitData().value)

                val downstreamRows = mutableListOf<String?>()
                val rowDeliveries = Channel<String?>(Channel.UNLIMITED)
                val chronology = mutableListOf<String>()
                val readerJob = backgroundScope.launch {
                    coordinated.reader(key).collect { row ->
                        downstreamRows += row
                        chronology += "row:$row"
                        rowDeliveries.send(row)
                    }
                }
                assertEquals("base", rowDeliveries.receive())
                chronology.clear()

                val signalDeliveries = Channel<StoreKey>(Channel.UNLIMITED)
                coordinated.awaitRetirementSignalSubscribers(expected = 1)
                val signalJob = backgroundScope.launch {
                    coordinated.overlay.changes.collect { signaledKey ->
                        chronology += "signal"
                        signalDeliveries.send(signaledKey)
                    }
                }
                coordinated.awaitRetirementSignalSubscribers(expected = 2)
                val observingHandle =
                    object : StoreWriteHandle<CoordinatorKey, String> {
                        override suspend fun apply(
                            key: CoordinatorKey,
                            value: String,
                        ) {
                            chronology += "apply:start"
                            assertEquals(listOf<String?>("base"), downstreamRows)
                            assertTrue(signalDeliveries.tryReceive().isFailure)
                            realHandle.apply(key, value)
                            assertEquals(listOf<String?>("base"), downstreamRows)
                            chronology += "apply:end"
                        }

                        override suspend fun markStale(key: CoordinatorKey) {
                            realHandle.markStale(key)
                        }

                        override suspend fun confirmFresh(
                            key: CoordinatorKey,
                            etag: String?,
                        ) {
                            chronology += "confirm:start"
                            assertEquals(listOf<String?>("base"), downstreamRows)
                            assertTrue(signalDeliveries.tryReceive().isFailure)
                            realHandle.confirmFresh(key, etag)
                            assertEquals(listOf<String?>("base"), downstreamRows)
                            chronology += "confirm:end"
                        }
                    }

                coordinated.acknowledge(
                    key = key,
                    echo = "echo",
                    etag = "etag-1",
                    handle = observingHandle,
                    retire = {
                        chronology += "retire"
                        delegate.retireMutation()
                        coordinated.signalRetired(key)
                        coordinated.signalRetired(key)
                    },
                )
                testScheduler.runCurrent()

                assertEquals("echo", awaitData().value)
                expectNoEvents()
                assertEquals(key, signalDeliveries.receive())
                assertTrue(signalDeliveries.tryReceive().isFailure)
                assertEquals(fetchesBeforeAcknowledge, fetches)
                assertEquals(2, delegate.writeCalls)
                assertEquals(
                    listOf("base", null, "echo", "base", null, "echo"),
                    delegate.rawWriteNotifications,
                )
                assertEquals(1, delegate.retirementCalls)
                assertFalse(delegate.pendingMutation)
                assertEquals(listOf<String?>("base", "echo"), downstreamRows)
                assertEquals(
                    listOf(
                        "retire",
                        "apply:start",
                        "apply:end",
                        "confirm:start",
                        "confirm:end",
                        "row:echo",
                        "signal",
                    ),
                    chronology,
                )
                localCollector.cancel()
                signalJob.cancel()
                readerJob.cancel()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun cancellationRollback_discardsRowsAndSignal_recapturesAuthority_andReopensGate() = runTest {
        val key = CoordinatorKey("rollback")
        val delegate = NoisyTransactionalSourceOfTruth(initial = "base")
        val overlay = PendingMutationOverlay(delegate)
        val coordinated = CoordinatedTransactionalSourceOfTruth(delegate, overlay)
        var fetches = 0
        val store = store<CoordinatorKey, String> {
            fetcher {
                fetches++
                "network"
            }
            persistence(coordinated)
            overlay(coordinated.overlay)
        }

        try {
            val realHandle = checkNotNull(store.runtime()).writeHandle
            realHandle.apply(key, "base")
            realHandle.confirmFresh(key, etag = "seed")
            val localValues = Channel<String>(Channel.UNLIMITED)
            val localCollector = backgroundScope.launch {
                store.stream(key, Freshness.LocalOnly).collect { result ->
                    if (result is StoreResult.Data<String>) localValues.send(result.value)
                }
            }
            assertEquals("base+pending", localValues.receive())
            delegate.awaitReaderStarts(expected = 1)
            val beforeReplay = overlay.completedApplicationCount
            delegate.releaseReaderRows()
            overlay.awaitApplicationAfter(beforeReplay)
            delegate.resetWriteObservations()
            val fetchesBeforeAcknowledge = fetches

            val downstreamRows = mutableListOf<String?>()
            val rowDeliveries = Channel<String?>(Channel.UNLIMITED)
            val readerJob = backgroundScope.launch {
                coordinated.reader(key).collect { row ->
                    downstreamRows += row
                    rowDeliveries.send(row)
                }
            }
            assertEquals("base", rowDeliveries.receive())

            val signalDeliveries = Channel<StoreKey>(Channel.UNLIMITED)
            coordinated.awaitRetirementSignalSubscribers(expected = 1)
            val signalJob = backgroundScope.launch {
                coordinated.overlay.changes.collect(signalDeliveries::send)
            }
            coordinated.awaitRetirementSignalSubscribers(expected = 2)

            store.stream(key, Freshness.CachedOrFetch).test {
                assertEquals("base+pending", awaitData().value)
                var applyCalls = 0
                var confirmCalls = 0
                val unusedHandle =
                    object : StoreWriteHandle<CoordinatorKey, String> {
                        override suspend fun apply(
                            key: CoordinatorKey,
                            value: String,
                        ) {
                            applyCalls++
                        }

                        override suspend fun markStale(key: CoordinatorKey) = Unit

                        override suspend fun confirmFresh(
                            key: CoordinatorKey,
                            etag: String?,
                        ) {
                            confirmCalls++
                        }
                    }

                val retireReached = CompletableDeferred<Unit>()
                val holdRetire = CompletableDeferred<Unit>()
                val acknowledgement = backgroundScope.launch {
                    coordinated.acknowledge(
                        key = key,
                        echo = "echo",
                        etag = null,
                        handle = unusedHandle,
                        retire = {
                            delegate.retireMutation()
                            coordinated.signalRetired(key)
                            coordinated.signalRetired(key)
                            retireReached.complete(Unit)
                            holdRetire.await()
                        },
                    )
                }
                retireReached.await()
                acknowledgement.cancel(CancellationException("cancel during transaction"))
                acknowledgement.join()
                testScheduler.runCurrent()

                assertTrue(acknowledgement.isCancelled)
                assertEquals("base", rowDeliveries.receive())
                assertEquals(listOf<String?>("base", "base"), downstreamRows)
                assertEquals(listOf("base", null, "echo"), delegate.rawWriteNotifications)
                assertEquals(1, delegate.writeCalls)
                assertEquals(1, delegate.retirementCalls)
                assertTrue(delegate.pendingMutation)
                assertEquals(0, applyCalls)
                assertEquals(0, confirmCalls)
                assertTrue(signalDeliveries.tryReceive().isFailure)
                expectNoEvents()
                assertEquals(fetchesBeforeAcknowledge, fetches)
                cancelAndIgnoreRemainingEvents()
            }

            localCollector.cancel()
            delegate.publishAuthoritative("later")
            testScheduler.runCurrent()

            assertEquals("later", rowDeliveries.receive())
            assertEquals(listOf<String?>("base", "base", "later"), downstreamRows)
            signalJob.cancel()
            readerJob.cancel()
        } finally {
            store.close()
        }
    }

    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitData(): StoreResult.Data<String> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<String>) return item
            assertIs<StoreResult.Loading>(item)
        }
    }

    private suspend fun TestScope.exerciseFailedAdoption(
        failurePoint: AdoptionFailurePoint,
        failure: Throwable,
    ): Throwable {
        val key = CoordinatorKey("failed-${failurePoint.name.lowercase()}")
        val delegate = BufferedReaderTransactionalSourceOfTruth(initial = "base")
        val coordinated =
            CoordinatedTransactionalSourceOfTruth(delegate, PendingFreeOverlay)
        val rows = Channel<String?>(Channel.UNLIMITED)
        val reader = backgroundScope.launch {
            coordinated.reader(key).collect(rows::send)
        }
        assertEquals("base", rows.receive())
        val signals = Channel<StoreKey>(Channel.UNLIMITED)
        val signalReader = backgroundScope.launch {
            coordinated.overlay.changes.collect(signals::send)
        }
        coordinated.awaitRetirementSignalSubscribers(expected = 1)
        val handle =
            object : StoreWriteHandle<CoordinatorKey, String> {
                override suspend fun apply(
                    key: CoordinatorKey,
                    value: String,
                ) {
                    if (failurePoint == AdoptionFailurePoint.APPLY) throw failure
                }

                override suspend fun markStale(key: CoordinatorKey) = Unit

                override suspend fun confirmFresh(
                    key: CoordinatorKey,
                    etag: String?,
                ) {
                    if (failurePoint == AdoptionFailurePoint.CONFIRM) throw failure
                }
            }

        val thrown =
            try {
                coordinated.acknowledge(
                    key = key,
                    echo = "echo",
                    etag = null,
                    handle = handle,
                    retire = {
                        coordinated.signalRetired(key)
                        coordinated.signalRetired(key)
                    },
                )
                fail("acknowledgement should propagate the adoption failure")
            } catch (caught: Throwable) {
                caught
            }

        assertEquals("echo", rows.receive())
        testScheduler.runCurrent()
        assertTrue(signals.tryReceive().isFailure)

        delegate.publishAuthoritative("later")
        assertEquals("later", rows.receive())
        assertTrue(signals.tryReceive().isFailure)
        reader.cancel()
        signalReader.cancel()
        return thrown
    }
}
