package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Deterministic proofs for the private base/revision/generation projection protocol. */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class OverlayProjectionProtocolTest {
    private val key = TestKey("overlay-protocol")

    @Test
    fun projectionVocabulary_keepsExactThreeVariants() {
        val envelope =
            ValueEnvelope(
                value = "v",
                origin = Origin.SOT,
                meta = null,
                staleEpochAtCommit = 0L,
            )

        assertSame(envelope, assertIs<Projection.Value<String>>(Projection.Value(envelope)).envelope)
        assertEquals("optimistic", assertIs<Projection.Overlaid<String>>(Projection.Overlaid("optimistic")).value)
        assertSame(Projection.Absent, Projection.Absent)
    }

    @Test
    fun staleV1Apply_isDiscardedAfterResidenceAdvancesToV2() = runTest {
        val overlay = CountingOverlay<String>({ base -> base?.plus("+projected") })
        val staleGate = SuspendGate()
        var blockV1 = true
        val harness =
            stringEngine(
                overlay = overlay,
                fetcher = { FetcherResult.Success("v1") },
                afterProjectionApplyTestGate = { base ->
                    if (base == "v1" && blockV1) {
                        blockV1 = false
                        staleGate.pause()
                    }
                },
            )

        try {
            harness.engine.stream(Freshness.CachedOrFetch).test {
                assertIs<StoreResult.Loading>(awaitItem())
                staleGate.awaitEntered()

                val write = async(Dispatchers.Default) { harness.engine.applyWrite("v2") }
                awaitFromDefault { write.await() }
                staleGate.release()

                val data = awaitDataValue("v2+projected")
                assertEquals(Origin.OVERLAY, data.origin)
                assertFalse(seenDataValues.contains("v1+projected"))
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            staleGate.release()
            harness.close()
        }
    }

    @Test
    fun sameEnvelopeAtNewRevision_cannotConsumeOldReadiness() = runTest {
        val value = RefValue("same", "base")
        val source = SharedFlowSourceOfTruth<TestKey, RefValue>()
        val projectionNumber = MutableStateFlow(0)
        val completedProjections = Channel<RefValue>(Channel.UNLIMITED)
        val overlay = CountingOverlay<RefValue>({ base ->
            base?.let {
                val projected =
                    RefValue(
                        id = it.id,
                        tag = "projection-${projectionNumber.updateAndGet { number -> number + 1 }}",
                    )
                check(completedProjections.trySend(projected).isSuccess)
                projected
            }
        })
        val staleGate = SuspendGate()
        val readerGate = SuspendGate()
        val blockProjection = MutableStateFlow(false)
        val blockReader = MutableStateFlow(false)
        val harness =
            refEngine(
                overlay = overlay,
                sot = source,
                fetcher = { FetcherResult.Success(value) },
                afterProjectionApplyTestGate = { base ->
                    if (base === value && blockProjection.compareAndSet(true, false)) {
                        staleGate.pause()
                    }
                },
                beforeReaderDeliveryTestGate = {
                    if (blockReader.compareAndSet(true, false)) {
                        readerGate.pause()
                    }
                },
            )

        try {
            harness.engine.stream(Freshness.CachedOrFetch).test {
                awaitRefData()
                overlay.clearCalls()
                while (completedProjections.tryReceive().isSuccess) Unit
                blockProjection.value = true
                overlay.signals.emit(key)
                staleGate.awaitEntered()
                assertSame(value, overlay.awaitCall())
                val rejectedTag = completedProjections.receive().tag

                blockReader.value = true
                source.write(key, value)
                readerGate.awaitEntered() // mapping and its revision bump already happened
                staleGate.release()

                val latestCall = overlay.awaitCall()
                assertSame(value, latestCall)
                val acceptedTag = completedProjections.receive().tag
                readerGate.release()
                val latest = awaitDataTag(acceptedTag)
                assertEquals(Origin.OVERLAY, latest.origin)
                assertFalse(seenDataTags.contains(rejectedTag))
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            staleGate.release()
            readerGate.release()
            harness.close()
        }
    }

    @Test
    fun queuedReady_cannotRenderAfterResidenceAdvances() = runTest {
        val staleDelivery = SuspendGate()
        val staleDelivered = SuspendGate()
        val initialReader = SuspendGate()
        val newerReader = SuspendGate()
        val source = ContractSourceOfTruth<String>()
        var blockStaleDelivery = false
        var watchStaleDelivered = false
        var blockInitialReader = true
        var blockNewerReader = false
        var suffix = "initial"
        val overlay = CountingOverlay<String>({ base -> base?.plus("+$suffix") })
        val harness =
            stringEngine(
                overlay = overlay,
                sot = source,
                fetcher = { awaitCancellation() },
                beforeReaderDeliveryLockTestGate = { record ->
                    if (
                        blockNewerReader &&
                        record is ReaderRecord.Row &&
                        record.envelope.value == "v2"
                    ) {
                        blockNewerReader = false
                        newerReader.pause()
                    }
                },
                beforeReaderDeliveryTestGate = {
                    if (blockInitialReader) {
                        blockInitialReader = false
                        initialReader.pause()
                    }
                },
                beforeProjectionDeliveryLockTestGate = {
                    if (blockStaleDelivery) {
                        blockStaleDelivery = false
                        staleDelivery.pause()
                    }
                },
                afterProjectionDeliveryTestGate = {
                    if (watchStaleDelivered) {
                        watchStaleDelivered = false
                        staleDelivered.pause()
                    }
                },
            )

        try {
            harness.engine.applyWrite("v1")
            harness.engine.stream(Freshness.LocalOnly).test {
                awaitDataValue("v1+initial")
                source.readerStarted.await()
                initialReader.awaitEntered()

                suffix = "barrier"
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("v1", overlay.awaitCall())
                initialReader.release()
                initialReader.awaitExited()
                awaitDataValue("v1+barrier")

                suffix = "stale"
                blockStaleDelivery = true
                watchStaleDelivered = true
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("v1", overlay.awaitCall())
                staleDelivery.awaitEntered()

                suffix = "latest"
                blockNewerReader = true
                harness.engine.applyWrite("v2")
                newerReader.awaitEntered()
                while (overlay.awaitCall() != "v2") Unit

                staleDelivery.release()
                staleDelivered.awaitEntered()
                staleDelivered.release()
                newerReader.release()
                awaitDataValue("v2+latest")
                assertFalse(seenDataValues.contains("v1+stale"))
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            staleDelivery.release()
            staleDelivered.release()
            initialReader.release()
            newerReader.release()
            harness.close()
        }
    }

    @Test
    fun queuedOlderReady_cannotReplayAfterFailureFlushesLatestGeneration() = runTest {
        val fetch = SuspendGate()
        val oldDelivery = SuspendGate()
        val boom = IllegalStateException("offline")
        var calls = 0
        var suffix = "initial"
        var blockOldDelivery = false
        val overlay = CountingOverlay<String>({ base -> base?.plus("+$suffix") })
        val harness =
            stringEngine(
                overlay = overlay,
                fetcher = {
                    when (++calls) {
                        1 -> FetcherResult.Success("v")
                        2 -> {
                            fetch.pause()
                            FetcherResult.Error(boom)
                        }
                        else -> error("unexpected fetch $calls")
                    }
                },
                beforeProjectionDeliveryLockTestGate = {
                    if (blockOldDelivery) {
                        blockOldDelivery = false
                        oldDelivery.pause()
                    }
                },
            )

        try {
            harness.engine.stream(Freshness.StaleIfError).test {
                awaitDataValue("v+initial")
                harness.engine.invalidate()
                fetch.awaitEntered()

                suffix = "old"
                blockOldDelivery = true
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("v", overlay.awaitCall())
                oldDelivery.awaitEntered()

                suffix = "latest"
                overlay.signals.emit(key)
                assertEquals("v", overlay.awaitCall())
                fetch.release()

                assertEquals("v+latest", awaitDataValue().value)
                assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(awaitItem()).error)

                oldDelivery.release()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            fetch.release()
            oldDelivery.release()
            harness.close()
        }
    }

    @Test
    fun failureFlush_replansWhenAuthorizedProjectionObsoletes() = runTest {
        val fetch = SuspendGate()
        val oldProjection = SuspendGate()
        val readerMapping = SuspendGate()
        val refreshProjectionDelivery = SuspendGate()
        val outcomeDelivery = SuspendGate()
        val firstReadiness = SuspendGate()
        val secondReadiness = SuspendGate()
        val boom = IllegalStateException("offline")
        var suffix = "initial"
        var blockOldProjection = false
        var deliverRefreshProjection = false
        var watchReadiness = false
        var readinessCalls = 0
        val overlay = CountingOverlay<String>({ base -> base?.plus("+$suffix") })
        val source = ContractSourceOfTruth<String>()
        val harness =
            stringEngine(
                overlay = overlay,
                sot = source,
                fetcher = {
                    fetch.pause()
                    FetcherResult.Error(boom)
                },
                beforeReaderRecordMappingTestGate = { readerMapping.pause() },
                afterProjectionApplyTestGate = { base ->
                    if (base == "v1" && blockOldProjection) {
                        blockOldProjection = false
                        oldProjection.pause()
                    }
                },
                afterProjectionDeliveryTestGate = {
                    if (deliverRefreshProjection) {
                        deliverRefreshProjection = false
                        refreshProjectionDelivery.pause()
                    }
                },
                beforeTicketOutcomeDeliveryTestGate = { outcomeDelivery.pause() },
                beforeProjectionReadinessWaitTestGate = {
                    if (watchReadiness) {
                        when (++readinessCalls) {
                            1 -> firstReadiness.pause()
                            2 -> secondReadiness.pause()
                        }
                    }
                },
            )

        try {
            harness.engine.applyWrite("v1")
            overlay.awaitCallValue("v1")
            harness.engine.stream(Freshness.StaleIfError).test {
                awaitDataValue("v1+initial")
                source.readerStarted.await()
                readerMapping.awaitEntered()
                harness.engine.invalidate()
                fetch.awaitEntered()

                suffix = "refreshing"
                deliverRefreshProjection = true
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("v1", overlay.awaitCall())
                refreshProjectionDelivery.awaitEntered()
                refreshProjectionDelivery.release()
                refreshProjectionDelivery.awaitExited()
                awaitDataValue("v1+refreshing")

                suffix = "latest"
                blockOldProjection = true
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("v1", overlay.awaitCall())
                oldProjection.awaitEntered()

                fetch.release()
                outcomeDelivery.awaitEntered()
                watchReadiness = true
                outcomeDelivery.release()
                outcomeDelivery.awaitExited()
                firstReadiness.awaitEntered()

                val write = async(Dispatchers.Default) { harness.engine.applyWrite("v2") }
                awaitFromDefault { write.await() }
                val nextItem = async { awaitItem() }
                val secondWait = async(Dispatchers.Default) { secondReadiness.awaitEntered() }
                firstReadiness.release()

                val winner =
                    select<String> {
                        secondWait.onAwait { "replanned" }
                        nextItem.onAwait { "public-item" }
                    }
                assertEquals("replanned", winner)

                oldProjection.release()
                assertEquals("v2", overlay.awaitCall())
                secondReadiness.release()
                val projected = assertIs<StoreResult.Data<String>>(nextItem.await())
                assertEquals("v2+latest", projected.value)
                assertEquals(Origin.OVERLAY, projected.origin)
                assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(awaitItem()).error)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            fetch.release()
            oldProjection.release()
            readerMapping.release()
            refreshProjectionDelivery.release()
            outcomeDelivery.release()
            firstReadiness.release()
            secondReadiness.release()
            harness.close()
        }
    }

    @Test
    fun pendingOldBase_obsoletesWaiterAndQueuedWriteCompletes() = runTest {
        val overlay = CountingOverlay<String>({ base -> base?.plus("+op") })
        val pendingGate = SuspendGate()
        var blockNull = true
        val harness =
            stringEngine(
                overlay = overlay,
                fetcher = { awaitCancellation() },
                beforeProjectionApplyTestGate = { base ->
                    if (base == null && blockNull) {
                        blockNull = false
                        pendingGate.pause()
                    }
                },
            )

        try {
            harness.engine.stream(Freshness.LocalOnly).test {
                pendingGate.awaitEntered()
                val write = async(Dispatchers.Default) { harness.engine.applyWrite("confirmed") }
                awaitFromDefault { write.await() }
                pendingGate.release()

                val data = awaitDataValue("confirmed+op")
                assertEquals(Origin.OVERLAY, data.origin)

                overlay.transform = { it }
                overlay.signals.emit(key)
                val retired = awaitDataValue("confirmed")
                assertTrue(retired.origin == Origin.SOT || retired.origin == Origin.MEMORY)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            pendingGate.release()
            harness.close()
        }
    }

    @Test
    fun foreignDirectOwner_sameReferenceBaselineRemainsLive() = runTest {
        val resident = RefValue("same", "base")
        val secondFetch = SuspendGate()
        val outcomeGate = SuspendGate()
        var calls = 0
        val overlay = CountingOverlay<RefValue>({ it })
        val harness =
            refEngine(
                overlay = overlay,
                fetcher = {
                    when (++calls) {
                        1 -> FetcherResult.Success(resident, etag = "e1")
                        2 -> {
                            secondFetch.pause()
                            FetcherResult.NotModified("e2")
                        }
                        else -> error("unexpected fetch $calls")
                    }
                },
                beforeTicketOutcomeDeliveryTestGate = {
                    if (calls == 2) outcomeGate.pause()
                },
            )

        try {
            harness.engine.stream(Freshness.CachedOrFetch).test {
                awaitDataTag("base")
                harness.engine.invalidate()
                secondFetch.awaitEntered()
                secondFetch.release()
                outcomeGate.awaitEntered() // refreshed direct-owner envelope is now residence

                overlay.transform = { base -> base?.let { RefValue(it.id, "same-ref-overlay") } }
                overlay.signals.emit(key)
                val projected = awaitDataTag("same-ref-overlay")
                assertEquals(Origin.OVERLAY, projected.origin)
                outcomeGate.release()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            secondFetch.release()
            outcomeGate.release()
            harness.close()
        }
    }

    @Test
    fun obsoleteForeignWait_restoresPreviousVisibleAuthorization() = runTest {
        val visible = RefValue("visible", "base")
        val interloper = RefValue("interloper", "base")
        val settleMarker = RefValue("visible", "settle-marker")
        val projected = RefValue("visible", "foreign-overlay")
        val source = ContractSourceOfTruth<RefValue>()
        val interloperApply = SuspendGate()
        val interloperReadiness = SuspendGate()
        val initialVisibleReader = SuspendGate()
        val visibleEchoMapping = SuspendGate()
        val outcomeDelivery = SuspendGate()
        val projectionDelivered = SuspendGate()
        val telemetry = RecordingTelemetry()
        var blockInterloperApply = false
        var blockInterloperReadiness = false
        var blockInitialVisibleReader = true
        var blockVisibleEchoMapping = false
        var watchProjectionDelivery = false
        val overlay = CountingOverlay<RefValue>({ it })
        val harness =
            refEngine(
                overlay = overlay,
                sot = source,
                fetcher = { FetcherResult.NotModified("fresh") },
                beforeReaderRecordMappingTestGate = {
                    if (blockVisibleEchoMapping) {
                        blockVisibleEchoMapping = false
                        visibleEchoMapping.pause()
                    }
                },
                afterProjectionApplyTestGate = { base ->
                    if (base === interloper && blockInterloperApply) {
                        blockInterloperApply = false
                        interloperApply.pause()
                    }
                },
                beforeProjectionReadinessWaitTestGate = {
                    if (blockInterloperReadiness) {
                        blockInterloperReadiness = false
                        interloperReadiness.pause()
                    }
                },
                beforeReaderDeliveryTestGate = {
                    if (blockInitialVisibleReader) {
                        blockInitialVisibleReader = false
                        initialVisibleReader.pause()
                    }
                },
                beforeTicketOutcomeDeliveryTestGate = { outcomeDelivery.pause() },
                afterProjectionDeliveryTestGate = {
                    if (
                        watchProjectionDelivery &&
                        telemetry.serves.lastOrNull() == Origin.OVERLAY
                    ) {
                        watchProjectionDelivery = false
                        projectionDelivered.pause()
                    }
                },
                telemetry = telemetry,
            )

        try {
            harness.engine.applyWrite(visible)
            overlay.awaitCallValue(visible)
            harness.engine.stream(Freshness.LocalOnly).test {
                assertSame(visible, awaitRefData().value)
                source.readerStarted.await()
                initialVisibleReader.awaitEntered()
                overlay.transform = { base -> if (base === visible) settleMarker else base }
                overlay.signals.emit(key)
                initialVisibleReader.release()
                initialVisibleReader.awaitExited()
                assertSame(settleMarker, awaitDataTag("settle-marker").value)
                telemetry.awaitServe(Origin.OVERLAY)
                telemetry.clear()

                overlay.transform = { it }
                overlay.signals.emit(key)
                assertSame(visible, awaitDataTag("base").value)
                telemetry.awaitServe()
                telemetry.clear()

                blockInterloperApply = true
                blockInterloperReadiness = true
                source.write(key, interloper)
                interloperApply.awaitEntered()
                interloperReadiness.awaitEntered()

                blockVisibleEchoMapping = true
                harness.engine.applyWrite(visible)
                val fresh = async(Dispatchers.Default) { harness.engine.get(Freshness.MustBeFresh) }
                outcomeDelivery.awaitEntered()

                overlay.transform = { base -> if (base === visible) projected else base }
                telemetry.clear()
                interloperReadiness.release()
                visibleEchoMapping.awaitEntered()

                watchProjectionDelivery = true
                interloperApply.release()
                overlay.awaitCallValue(visible)
                projectionDelivered.awaitEntered()

                assertEquals(listOf(Origin.OVERLAY), telemetry.serves)
                projectionDelivered.release()
                assertSame(projected, awaitRefData().value)

                outcomeDelivery.release()
                assertSame(visible, awaitFromDefault { fresh.await() })
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            interloperApply.release()
            interloperReadiness.release()
            initialVisibleReader.release()
            visibleEchoMapping.release()
            outcomeDelivery.release()
            projectionDelivered.release()
            harness.close()
        }
    }

    @Test
    fun foreignDirectOwner_equalButDistinctReaderFirstReusesExactAuthorizedBaseline() =
        runForeignDirectOwnerEqualDistinctOrdering(ForeignOwnerOrdering.READER_FIRST)

    @Test
    fun foreignDirectOwner_equalButDistinctOwnerFirstNeverReusesOlderBaseline() =
        runForeignDirectOwnerEqualDistinctOrdering(ForeignOwnerOrdering.OWNER_FIRST)

    private fun runForeignDirectOwnerEqualDistinctOrdering(ordering: ForeignOwnerOrdering) = runTest {
        val older = RefValue("equal", "base")
        val newer = RefValue("equal", "base")
        val mustNotLeak = RefValue("equal", "must-not-leak")
        assertEquals(older, newer)
        assertTrue(older !== newer)
        val fetchGate = SuspendGate()
        val outcomeGate = SuspendGate()
        val preWriteMapping = SuspendGate()
        val orderingMapping = SuspendGate()
        val trailingMapping = SuspendGate()
        val initialObserverProjection = SuspendGate()
        val writerProjection = SuspendGate()
        val ownerProjection = SuspendGate()
        val targetProjection = SuspendGate()
        val projectionAfterGates = Channel<SuspendGate>(Channel.UNLIMITED)
        val telemetry = RecordingTelemetry()
        val source = ContractSourceOfTruth<RefValue>()
        var calls = 0
        var readerMappingCalls = 0
        val overlay = CountingOverlay<RefValue>({ it })
        val harness =
            refEngine(
                overlay = overlay,
                sot = source,
                fetcher = {
                    when (++calls) {
                        1 -> {
                            fetchGate.pause()
                            FetcherResult.NotModified("e2")
                        }
                        else -> error("unexpected fetch $calls")
                    }
                },
                beforeTicketOutcomeDeliveryTestGate = {
                    if (calls == 1) outcomeGate.pause()
                },
                beforeReaderRecordMappingTestGate = {
                    when (++readerMappingCalls) {
                        1 -> {
                            preWriteMapping.pause()
                            orderingMapping.pause()
                        }
                        else -> trailingMapping.pause()
                    }
                },
                afterProjectionDeliveryTestGate = {
                    projectionAfterGates.tryReceive().getOrNull()?.pause()
                },
                telemetry = telemetry,
            )

        try {
            harness.engine.applyWrite(older)
            overlay.awaitCallValue(older)
            overlay.clearCalls()
            check(projectionAfterGates.trySend(initialObserverProjection).isSuccess)
            harness.engine.stream(Freshness.CachedOrFetch).test {
                val initial = awaitRefData()
                assertSame(older, initial.value)
                assertEquals(Origin.SOT, initial.origin)
                source.readerStarted.await()
                preWriteMapping.awaitEntered()
                initialObserverProjection.awaitEntered()
                initialObserverProjection.release()
                initialObserverProjection.awaitExited()
                telemetry.clear()
                overlay.clearCalls()

                check(projectionAfterGates.trySend(writerProjection).isSuccess)
                harness.engine.applyWrite(newer)
                assertSame(newer, overlay.awaitCall())
                writerProjection.awaitEntered()
                writerProjection.release()
                writerProjection.awaitExited()
                overlay.clearCalls()

                preWriteMapping.release()
                preWriteMapping.awaitExited()
                orderingMapping.awaitEntered()
                if (ordering == ForeignOwnerOrdering.READER_FIRST) {
                    orderingMapping.release()
                    orderingMapping.awaitExited()
                    trailingMapping.awaitEntered()
                    trailingMapping.release()
                    trailingMapping.awaitExited()
                    val current = awaitRefData()
                    assertSame(newer, current.value)
                    assertEquals(Origin.SOT, current.origin)
                    assertEquals(Origin.SOT, telemetry.awaitServe(Origin.SOT))
                }

                val foreignOwner = async(Dispatchers.Default) {
                    harness.engine.get(Freshness.MustBeFresh)
                }
                fetchGate.awaitEntered()
                check(projectionAfterGates.trySend(ownerProjection).isSuccess)
                fetchGate.release()
                outcomeGate.awaitEntered()
                ownerProjection.awaitEntered()
                ownerProjection.release()
                ownerProjection.awaitExited()

                overlay.transform = { mustNotLeak }
                overlay.clearCalls()
                check(projectionAfterGates.trySend(targetProjection).isSuccess)
                overlay.signals.emit(key)
                assertSame(newer, overlay.awaitCall())
                targetProjection.awaitEntered()
                targetProjection.release()
                targetProjection.awaitExited()

                if (ordering == ForeignOwnerOrdering.READER_FIRST) {
                    val projected = awaitRefData()
                    assertSame(mustNotLeak, projected.value)
                    assertEquals(Origin.OVERLAY, projected.origin)
                    assertEquals(Origin.OVERLAY, telemetry.awaitServe(Origin.OVERLAY))
                    outcomeGate.release()
                    assertSame(newer, awaitFromDefault { foreignOwner.await() })
                    cancelAndIgnoreRemainingEvents()
                } else {
                    expectNoEvents()
                    assertFalse(telemetry.serves.contains(Origin.OVERLAY))
                    outcomeGate.release()
                    assertSame(newer, awaitFromDefault { foreignOwner.await() })
                    cancelAndIgnoreRemainingEvents()
                }
            }
        } finally {
            fetchGate.release()
            outcomeGate.release()
            preWriteMapping.release()
            orderingMapping.release()
            trailingMapping.release()
            initialObserverProjection.release()
            writerProjection.release()
            ownerProjection.release()
            targetProjection.release()
            harness.close()
        }
    }

    @Test
    fun policyWithheldNonNullBase_doesNotAuthorizeProjection() = runTest {
        var calls = 0
        val refresh = SuspendGate()
        val overlay = CountingOverlay<String>({ base -> base?.plus("+optimistic") })
        val store = store<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refresh.pause()
                        "v2"
                    }
                    else -> error("unexpected fetch $calls")
                }
            }
            overlay(overlay)
        }

        try {
            store.stream(key).test {
                awaitDataValue("v1+optimistic")
                cancelAndIgnoreRemainingEvents()
            }
            store.invalidate(key)
            store.stream(key, Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                refresh.awaitEntered()
                expectNoEvents()
                refresh.release()
                awaitDataValue("v2+optimistic")
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            refresh.release()
            store.close()
        }
    }

    @Test
    fun nullOverlay_preservesEqualButDistinctForeignBaselineReuse() = runTest {
        val visible = RefValue("visible", "visible")
        val mapped = RefValue("equal", "value")
        val ownerValue = RefValue("equal", "value")
        assertEquals(mapped, ownerValue)
        assertTrue(mapped !== ownerValue)
        val source = ContractSourceOfTruth<RefValue>()
        val mappedDelivery = SuspendGate()
        var blockMapped = false
        val harness =
            refEngine(
                overlay = null,
                sot = source,
                fetcher = { FetcherResult.NotModified("e1") },
                beforeReaderDeliveryLockTestGate = { record ->
                    if (
                        blockMapped &&
                        record is ReaderRecord.Row &&
                        record.envelope.value === mapped
                    ) {
                        blockMapped = false
                        mappedDelivery.pause()
                    }
                },
            )

        try {
            harness.engine.applyWrite(visible)
            harness.engine.stream(Freshness.LocalOnly).test {
                assertSame(visible, awaitRefData().value)
                source.readerStarted.await()

                blockMapped = true
                source.write(key, mapped)
                mappedDelivery.awaitEntered()
                harness.engine.applyWrite(ownerValue)
                assertSame(ownerValue, harness.engine.get(Freshness.MustBeFresh))

                mappedDelivery.release()
                assertSame(mapped, awaitRefData().value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            mappedDelivery.release()
            harness.close()
        }
    }

    @Test
    fun confirmedAbsenceAuthorizesCreate_readerFailureDoesNot() = runTest {
        val create = CountingOverlay<String>({ it ?: "optimistic-create" })
        val absentStore = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(create)
        }
        val readerBoom = IllegalStateException("reader failed")
        val liveReaderFailure = SuspendGate()
        val projectionDelivery = SuspendGate()
        val failingHarness =
            stringEngine(
                overlay = CountingOverlay<String>({ it ?: "must-not-appear" }),
                fetcher = { awaitCancellation() },
                sot = FailingReaderSourceOfTruth(readerBoom, liveReaderFailure),
                afterProjectionDeliveryTestGate = { projectionDelivery.pause() },
            )

        try {
            absentStore.stream(key).test {
                val created = awaitDataValue("optimistic-create")
                assertEquals(Origin.OVERLAY, created.origin)
                cancelAndIgnoreRemainingEvents()
            }
            failingHarness.engine.stream(Freshness.CachedOrFetch).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(failure.error)

                liveReaderFailure.awaitEntered()
                projectionDelivery.awaitEntered()
                expectNoEvents() // the ready optimistic-create snapshot was observed but rejected

                projectionDelivery.release()
                liveReaderFailure.release()
                val liveFailure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(liveFailure.error)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            liveReaderFailure.release()
            projectionDelivery.release()
            absentStore.close()
            failingHarness.close()
        }
    }

    @Test
    fun identityProjection_preservesLateCollectorMemoryOrigin() = runTest {
        val store = store<TestKey, String> {
            fetcher { "v" }
            overlay(CountingOverlay<String>({ it }))
        }

        try {
            assertEquals("v", store.get(key))
            store.stream(key, Freshness.LocalOnly).test {
                val data = awaitDataValue("v")
                assertEquals(Origin.MEMORY, data.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun revalidated_identityHasNoExtraData_andTelemetryUsesEffectiveOrigin() = runTest {
        val telemetry = RecordingTelemetry()
        revalidationScenario(
            overlay = CountingOverlay<String>({ it }),
            telemetry = telemetry,
        ) { turbine ->
            val revalidated = turbine.awaitNonDataTerminal()
            assertIs<StoreResult.Revalidated>(revalidated)
            turbine.expectNoEvents()
            assertEquals(listOf(Origin.FETCHER), telemetry.serves)
        }
    }

    @Test
    fun revalidated_overlaidOrdersProjectionThenLifecycle_andTelemetryUsesOverlay() = runTest {
        val telemetry = RecordingTelemetry()
        var suffix = "one"
        val overlay = CountingOverlay<String>({ base -> base?.plus("+$suffix") })
        revalidationScenario(
            overlay = overlay,
            telemetry = telemetry,
            beforeRelease = {
                suffix = "two"
                overlay.signals.emit(key)
            },
        ) { turbine ->
            val data = assertIs<StoreResult.Data<String>>(turbine.awaitItem())
            assertEquals("v+two", data.value)
            assertEquals(Origin.OVERLAY, data.origin)
            assertIs<StoreResult.Revalidated>(turbine.awaitItem())
            assertEquals(listOf(Origin.OVERLAY, Origin.OVERLAY), telemetry.serves)
        }
    }

    @Test
    fun revalidated_unchangedOverlaidHasNoExtraData_andSingleTelemetryHook() = runTest {
        val telemetry = RecordingTelemetry()
        revalidationScenario(
            overlay = CountingOverlay<String>({ base -> base?.let { "stable-overlay" } }),
            telemetry = telemetry,
        ) { turbine ->
            val terminal = turbine.awaitNonDataTerminal()
            val fetchFailure =
                (terminal as? StoreResult.Error)?.error as? StoreError.Fetch
            assertIs<StoreResult.Revalidated>(terminal, fetchFailure?.cause?.message)
            turbine.expectNoEvents()
            assertEquals(listOf(Origin.OVERLAY), telemetry.serves)
        }
    }

    @Test
    fun revalidated_absentOrdersLoadingThenLifecycle_andSkipsTelemetry() = runTest {
        val telemetry = RecordingTelemetry()
        var absent = false
        val overlay = CountingOverlay<String>({ base -> if (absent) null else base })
        revalidationScenario(
            overlay = overlay,
            telemetry = telemetry,
            beforeRelease = {
                absent = true
                overlay.signals.emit(key)
            },
        ) { turbine ->
            assertIs<StoreResult.Loading>(turbine.awaitItem())
            assertIs<StoreResult.Revalidated>(turbine.awaitItem())
            assertTrue(telemetry.serves.isEmpty())
        }
    }

    @Test
    fun failedFetch_flushesOverlaidProjectionBeforeError_andReportsServedStale() = runTest {
        failedProjectionScenario(projectAbsent = false) { events ->
            assertEquals(listOf("data:v+latest", "error:true"), events)
        }
    }

    @Test
    fun failedFetch_flushesAbsentProjectionBeforeError_andClearsServedStale() = runTest {
        failedProjectionScenario(projectAbsent = true) { events ->
            assertEquals(listOf("loading", "error:false"), events)
        }
    }

    @Test
    fun serverDelete_projectsExactAbsenceBeforeTerminalError() = runTest {
        var calls = 0
        val deletion = SuspendGate()
        val overlay = CountingOverlay<String>({ base -> base ?: "optimistic-after-delete" })
        val store = store<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v")
                    2 -> {
                        deletion.pause()
                        FetcherResult.Deleted
                    }
                    else -> error("unexpected fetch $calls")
                }
            }
            overlay(overlay)
        }

        try {
            store.stream(key).test {
                awaitDataValue("v")
                store.invalidate(key)
                deletion.awaitEntered()
                deletion.release()

                val optimistic = awaitDataValue("optimistic-after-delete")
                assertEquals(Origin.OVERLAY, optimistic.origin)
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Missing>(failure.error)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            deletion.release()
            store.close()
        }
    }

    @Test
    fun mustBeFreshInitialDelete_projectsExactAbsenceBeforeTerminalError() = runTest {
        val deletion = SuspendGate()
        val overlay = CountingOverlay<String>({ base -> base ?: "optimistic-after-delete" })
        val store = store<TestKey, String> {
            fetcherOfResult {
                deletion.pause()
                FetcherResult.Deleted
            }
            overlay(overlay)
        }

        try {
            store.runtime()!!.writeHandle.apply(key, "confirmed")
            store.stream(key, Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                deletion.awaitEntered()
                deletion.release()
                assertEquals("optimistic-after-delete", awaitDataValue().value)
                assertIs<StoreError.Missing>(assertIs<StoreResult.Error>(awaitItem()).error)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            deletion.release()
            store.close()
        }
    }

    @Test
    fun mustBeFreshInitialFailure_flushesLatestProjectionBeforeError() = runTest {
        val fetch = SuspendGate()
        val boom = IllegalStateException("offline")
        var projected = "one"
        val overlay = CountingOverlay<String>({ base -> base ?: projected })
        val store = store<TestKey, String> {
            fetcherOfResult {
                fetch.pause()
                FetcherResult.Error(boom)
            }
            overlay(overlay)
        }

        try {
            store.stream(key, Freshness.MustBeFresh).test {
                awaitDataValue("one")
                fetch.awaitEntered()
                overlay.clearCalls()
                projected = "two"
                overlay.signals.emit(key)
                assertEquals(null, overlay.awaitCall())
                fetch.release()

                assertEquals("two", awaitDataValue().value)
                assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(awaitItem()).error)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            fetch.release()
            store.close()
        }
    }

    @Test
    fun mustBeFreshWaitingFetch_observesOverlayTerminalForCurrentAndFutureStreams() = runTest {
        val boom = IllegalStateException("projection failed while fetch waits")
        val failChanges = CompletableDeferred<Unit>()
        val overlay =
            CountingOverlay<String>(
                changes = flow {
                    failChanges.await()
                    throw boom
                },
                transform = { base -> base },
            )
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(overlay)
        }

        try {
            store.runtime()!!.writeHandle.apply(key, "resident")
            store.stream(key, Freshness.LocalOnly).test {
                awaitDataValue("resident")
                cancelAndIgnoreRemainingEvents()
            }

            store.stream(key, Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                failChanges.complete(Unit)

                val terminal = assertIs<OverlayProjectionException>(awaitError())
                assertEquals(boom::class, terminal.cause!!::class)
                assertEquals(boom.message, terminal.cause?.message)
            }

            val futureFailure =
                runCatching {
                    store.stream(key, Freshness.MustBeFresh).collect { }
            }.exceptionOrNull()
            val futureTerminal = assertIs<OverlayProjectionException>(futureFailure)
            assertEquals(boom::class, futureTerminal.cause!!::class)
            assertEquals(boom.message, futureTerminal.cause?.message)
        } finally {
            failChanges.complete(Unit)
            store.close()
        }
    }

    @Test
    fun applyFailureWithLiveChangesCollector_retainsOriginalCauseForCurrentAndFutureStreams() =
        runTest {
            val boom = IllegalStateException("apply failed with live changes collector")
            val failProjection = MutableStateFlow(false)
            val signals = MutableSharedFlow<StoreKey>(replay = 1)
            val overlay =
                object : Overlay<TestKey, String> {
                    override val changes: Flow<StoreKey> = signals

                    override fun apply(
                        key: TestKey,
                        base: String?,
                    ): String? {
                        if (failProjection.value) throw boom
                        return base
                    }
                }
            val store = store<TestKey, String> {
                fetcher { awaitCancellation() }
                overlay(overlay)
            }

            try {
                store.runtime()!!.writeHandle.apply(key, "resident")
                store.stream(key, Freshness.LocalOnly).test {
                    awaitDataValue("resident")
                    cancelAndIgnoreRemainingEvents()
                }
                withContext(Dispatchers.Default) {
                    signals.subscriptionCount.first { count -> count > 0 }
                }

                store.stream(key, Freshness.MustBeFresh).test {
                    assertIs<StoreResult.Loading>(awaitItem())
                    failProjection.value = true
                    signals.emit(key)

                    val terminal = assertIs<OverlayProjectionException>(awaitError())
                    assertEquals(boom::class, terminal.cause!!::class)
                    assertEquals(boom.message, terminal.cause?.message)
                }

                assertTerminalCause(store, boom)
            } finally {
                store.close()
            }
        }

    @Test
    fun closeCancelsPendingReadiness_withoutTerminalizingCooperativeClose() = runTest {
        val pending = SuspendGate()
        val readiness = SuspendGate()
        val harness =
            stringEngine(
                overlay = CountingOverlay<String>({ it }),
                fetcher = { awaitCancellation() },
                beforeProjectionApplyTestGate = { pending.pause() },
                beforeProjectionReadinessWaitTestGate = { readiness.pause() },
            )

        try {
            val collection = async(Dispatchers.Default) {
                harness.engine.stream(Freshness.LocalOnly).collect { }
            }
            pending.awaitEntered()
            readiness.awaitEntered()
            harness.close()
            readiness.release()
            val failure = runCatching { awaitFromDefault { collection.await() } }.exceptionOrNull()
            assertIs<CancellationException>(failure)
        } finally {
            pending.release()
            readiness.release()
            harness.close()
        }
    }

    @Test
    fun throwingApplyTerminalizesCurrentAndFutureStreams() = runTest {
        val boom = IllegalStateException("apply failed")
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(ThrowingOverlay(boom, emptyFlow()))
        }

        try {
            assertTerminalCause(store, boom)
            assertTerminalCause(store, boom)
        } finally {
            store.close()
        }
    }

    @Test
    fun selfOriginatedCancellationFromApplyTerminalizesWhileEngineIsActive() = runTest {
        val boom = CancellationException("callback cancelled itself")
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(ThrowingOverlay(boom, emptyFlow()))
        }

        try {
            assertTerminalCause(store, boom)
        } finally {
            store.close()
        }
    }

    @Test
    fun failingChangesTerminalizesCurrentAndFutureStreams() = runTest {
        val boom = IllegalStateException("changes failed")
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(ThrowingOverlay(null, flow { throw boom }))
        }

        try {
            assertTerminalCause(store, boom)
            assertTerminalCause(store, boom)
        } finally {
            store.close()
        }
    }

    @Test
    fun selfOriginatedCancellationFromChangesTerminalizesWhileEngineIsActive() = runTest {
        val boom = CancellationException("changes cancelled itself")
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(ThrowingOverlay(null, flow { throw boom }))
        }

        try {
            assertTerminalCause(store, boom)
        } finally {
            store.close()
        }
    }

    @Test
    fun normalChangesCompletion_keepsResidenceProjectionActive() = runTest {
        val overlay = CountingOverlay<String>(changes = emptyFlow()) { base -> base?.plus("+op") }
        val store = store<TestKey, String> {
            fetcher { awaitCancellation() }
            overlay(overlay)
        }

        try {
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                store.runtime()!!.writeHandle.apply(key, "confirmed")
                val data = awaitDataValue("confirmed+op")
                assertEquals(Origin.OVERLAY, data.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun rapidSignals_haveExactAcceptedCountsIncludingEqualOutputAndMismatch() = runTest {
        val overlay = CountingOverlay<String>({ "stable" })
        val source = ContractSourceOfTruth<String>()
        val harness =
            stringEngine(
                overlay = overlay,
                sot = source,
                fetcher = { awaitCancellation() },
            )

        try {
            harness.engine.applyWrite("base")
            overlay.awaitCallValue("base")
            overlay.clearCalls()
            harness.engine.stream(Freshness.LocalOnly).test {
                awaitDataValue("stable")
                source.readerStarted.await()
                overlay.awaitCallValue("base")
                overlay.clearCalls()
                val callsBeforeSignals = overlay.callCount

                overlay.signals.emit(TestKey("different"))
                overlay.signals.emit(key)
                assertEquals("base", overlay.awaitCall())
                expectNoEvents() // equal projection still invokes apply but emits no duplicate Data

                overlay.signals.emit(key)
                assertEquals("base", overlay.awaitCall())
                expectNoEvents()
                assertEquals(callsBeforeSignals + 2, overlay.callCount)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun twoCollectors_doNotIncreaseGlobalApplyCount() = runTest {
        val overlay = CountingOverlay<String>({ "one-global-projection" })
        val source = ContractSourceOfTruth<String>()
        val harness =
            stringEngine(
                overlay = overlay,
                sot = source,
                fetcher = { awaitCancellation() },
            )

        try {
            harness.engine.applyWrite("base")
            overlay.awaitCallValue("base")
            overlay.clearCalls()
            turbineScope {
                val first = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("one-global-projection", first.awaitDataValue().value)
                source.readerStarted.await()
                overlay.awaitCallValue("base")
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("base", overlay.awaitCall())
                val callsBeforeSecondCollector = overlay.callCount

                val second = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("one-global-projection", second.awaitDataValue().value)
                overlay.clearCalls()
                overlay.signals.emit(key)
                assertEquals("base", overlay.awaitCall())
                assertEquals(callsBeforeSecondCollector + 1, overlay.callCount)
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun nullOverlay_preservesLandedBehaviorWithoutProjectionCallbacks() = runTest {
        val neverInstalled = CountingOverlay<String>({ error("must never run") })
        val plain = store<TestKey, String> { fetcher { "v" } }

        try {
            plain.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v", data.value)
                assertEquals(Origin.FETCHER, data.origin)
                expectNoEvents()
                assertEquals(0, neverInstalled.callCount)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            plain.close()
        }
    }

    private suspend fun TestScope.revalidationScenario(
        overlay: CountingOverlay<String>,
        telemetry: RecordingTelemetry,
        beforeRelease: suspend () -> Unit = {},
        verify: suspend (ReceiveTurbine<StoreResult<String>>) -> Unit,
    ) {
        var calls = 0
        val gate = SuspendGate()
        val store = store<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v", etag = "e1")
                    2 -> {
                        gate.pause()
                        FetcherResult.NotModified("e2")
                    }
                    else -> error("unexpected fetch $calls")
                }
            }
            overlay(overlay)
            telemetry(telemetry)
        }

        try {
            store.stream(key).test {
                awaitDataValue()
                telemetry.awaitServeCausally()
                telemetry.clear()
                store.invalidate(key)
                gate.awaitEnteredCausally()
                beforeRelease()
                gate.release()
                verify(this)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            gate.release()
            store.close()
        }
    }

    private suspend fun TestScope.failedProjectionScenario(
        projectAbsent: Boolean,
        verify: (List<String>) -> Unit,
    ) {
        var calls = 0
        var latest = false
        val fetchGate = SuspendGate()
        val projectionGate = SuspendGate()
        var blockLatest = false
        val boom = IllegalStateException("fetch failed")
        val overlay = CountingOverlay<String>({ base ->
            when {
                !latest -> base?.plus("+initial")
                projectAbsent -> null
                else -> base?.plus("+latest")
            }
        })
        val harness =
            stringEngine(
                overlay = overlay,
                fetcher = {
                    when (++calls) {
                        1 -> FetcherResult.Success("v")
                        2 -> {
                            fetchGate.pause()
                            FetcherResult.Error(boom)
                        }
                        else -> error("unexpected fetch $calls")
                    }
                },
                afterProjectionApplyTestGate = { base ->
                    if (base == "v" && blockLatest) {
                        blockLatest = false
                        projectionGate.pause()
                    }
                },
            )

        try {
            harness.engine.stream(Freshness.StaleIfError).test {
                awaitDataValue("v+initial")
                harness.engine.invalidate()
                fetchGate.awaitEntered()
                latest = true
                blockLatest = true
                overlay.signals.emit(key)
                projectionGate.awaitEntered()
                fetchGate.release()
                projectionGate.release()

                val events = mutableListOf<String>()
                while (events.none { it.startsWith("error:") }) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data<String> -> events += "data:${item.value}"
                        is StoreResult.Loading -> events += "loading"
                        is StoreResult.Error -> events += "error:${item.servedStale}"
                        is StoreResult.Revalidated -> events += "revalidated"
                    }
                }
                verify(events)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            fetchGate.release()
            projectionGate.release()
            harness.close()
        }
    }

    private fun TestScope.refEngine(
        overlay: Overlay<TestKey, RefValue>?,
        fetcher: suspend (TestKey) -> FetcherResult<RefValue>,
        sot: SourceOfTruth<TestKey, RefValue> = SharedFlowSourceOfTruth(),
        beforeReaderRecordMappingTestGate: suspend () -> Unit = {},
        beforeReaderDeliveryLockTestGate: suspend (ReaderRecord<RefValue>) -> Unit = {},
        beforeProjectionApplyTestGate: suspend (RefValue?) -> Unit = {},
        afterProjectionApplyTestGate: suspend (RefValue?) -> Unit = {},
        beforeReaderDeliveryTestGate: suspend () -> Unit = {},
        beforeTicketOutcomeDeliveryTestGate: suspend () -> Unit = {},
        beforeProjectionDeliveryLockTestGate: suspend () -> Unit = {},
        beforeProjectionDeliveryTestGate: suspend () -> Unit = {},
        afterProjectionDeliveryTestGate: suspend () -> Unit = {},
        beforeProjectionReadinessWaitTestGate: suspend () -> Unit = {},
        telemetry: StoreTelemetry? = null,
    ): EngineHarness<RefValue> =
        engineTyped(
            overlay,
            fetcher,
            sot,
            beforeReaderRecordMappingTestGate,
            beforeReaderDeliveryLockTestGate,
            beforeProjectionApplyTestGate,
            afterProjectionApplyTestGate,
            beforeReaderDeliveryTestGate,
            beforeTicketOutcomeDeliveryTestGate,
            beforeProjectionDeliveryLockTestGate,
            beforeProjectionDeliveryTestGate,
            afterProjectionDeliveryTestGate,
            beforeProjectionReadinessWaitTestGate,
            telemetry,
        )

    private fun TestScope.stringEngine(
        overlay: Overlay<TestKey, String>?,
        fetcher: suspend (TestKey) -> FetcherResult<String>,
        sot: SourceOfTruth<TestKey, String> = SharedFlowSourceOfTruth(),
        beforeReaderRecordMappingTestGate: suspend () -> Unit = {},
        beforeReaderDeliveryLockTestGate: suspend (ReaderRecord<String>) -> Unit = {},
        beforeProjectionApplyTestGate: suspend (String?) -> Unit = {},
        afterProjectionApplyTestGate: suspend (String?) -> Unit = {},
        beforeReaderDeliveryTestGate: suspend () -> Unit = {},
        beforeTicketOutcomeDeliveryTestGate: suspend () -> Unit = {},
        beforeProjectionDeliveryLockTestGate: suspend () -> Unit = {},
        beforeProjectionDeliveryTestGate: suspend () -> Unit = {},
        afterProjectionDeliveryTestGate: suspend () -> Unit = {},
        beforeProjectionReadinessWaitTestGate: suspend () -> Unit = {},
        telemetry: StoreTelemetry? = null,
    ): EngineHarness<String> =
        engineTyped(
            overlay,
            fetcher,
            sot,
            beforeReaderRecordMappingTestGate,
            beforeReaderDeliveryLockTestGate,
            beforeProjectionApplyTestGate,
            afterProjectionApplyTestGate,
            beforeReaderDeliveryTestGate,
            beforeTicketOutcomeDeliveryTestGate,
            beforeProjectionDeliveryLockTestGate,
            beforeProjectionDeliveryTestGate,
            afterProjectionDeliveryTestGate,
            beforeProjectionReadinessWaitTestGate,
            telemetry,
        )

    private fun <V : Any> TestScope.engineTyped(
        overlay: Overlay<TestKey, V>?,
        fetcher: suspend (TestKey) -> FetcherResult<V>,
        sot: SourceOfTruth<TestKey, V>,
        beforeReaderRecordMappingTestGate: suspend () -> Unit,
        beforeReaderDeliveryLockTestGate: suspend (ReaderRecord<V>) -> Unit,
        beforeProjectionApplyTestGate: suspend (V?) -> Unit,
        afterProjectionApplyTestGate: suspend (V?) -> Unit,
        beforeReaderDeliveryTestGate: suspend () -> Unit,
        beforeTicketOutcomeDeliveryTestGate: suspend () -> Unit,
        beforeProjectionDeliveryLockTestGate: suspend () -> Unit,
        beforeProjectionDeliveryTestGate: suspend () -> Unit,
        afterProjectionDeliveryTestGate: suspend () -> Unit,
        beforeProjectionReadinessWaitTestGate: suspend () -> Unit,
        telemetry: StoreTelemetry?,
    ): EngineHarness<V> {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.Default + job)
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher(fetcher),
                sot = sot,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = org.mobilenativefoundation.store6.core.FakeWallClock(0L),
                engineScope = scope,
                telemetry = telemetry,
                beforeReaderRecordMappingTestGate = beforeReaderRecordMappingTestGate,
                beforeReaderDeliveryLockTestGate = beforeReaderDeliveryLockTestGate,
                beforeReaderDeliveryTestGate = beforeReaderDeliveryTestGate,
                beforeTicketOutcomeDeliveryTestGate = beforeTicketOutcomeDeliveryTestGate,
                overlay = overlay,
                beforeProjectionApplyTestGate = beforeProjectionApplyTestGate,
                afterProjectionApplyTestGate = afterProjectionApplyTestGate,
                beforeProjectionDeliveryLockTestGate = beforeProjectionDeliveryLockTestGate,
                beforeProjectionDeliveryTestGate = beforeProjectionDeliveryTestGate,
                afterProjectionDeliveryTestGate = afterProjectionDeliveryTestGate,
                beforeProjectionReadinessWaitTestGate = beforeProjectionReadinessWaitTestGate,
            )
        return EngineHarness(engine, job)
    }

    private suspend fun assertTerminalCause(
        store: org.mobilenativefoundation.store6.core.Store<TestKey, String>,
        expected: Throwable,
    ) {
        val failure = runCatching { store.stream(key, Freshness.LocalOnly).collect { } }.exceptionOrNull()
        val terminal = assertIs<OverlayProjectionException>(failure)
        assertEquals(expected::class, terminal.cause!!::class)
        assertEquals(expected.message, terminal.cause?.message)
    }

    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String? = null,
    ): StoreResult.Data<String> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<String>) {
                seenDataValues += item.value
                if (expected == null || item.value == expected) return item
            }
        }
    }

    private val seenDataValues = mutableListOf<String>()

    private val seenDataTags = mutableListOf<String>()

    private suspend fun ReceiveTurbine<StoreResult<RefValue>>.awaitDataTag(
        expected: String,
    ): StoreResult.Data<RefValue> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<RefValue>) {
                seenDataTags += item.value.tag
                if (item.value.tag == expected) return item
            }
        }
    }

    private suspend fun ReceiveTurbine<StoreResult<RefValue>>.awaitRefData(): StoreResult.Data<RefValue> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<RefValue>) {
                seenDataTags += item.value.tag
                return item
            }
        }
    }

    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitNonDataTerminal(): StoreResult<String> {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Data<String> ->
                    error(
                        "unexpected extra Data(" +
                            "value=${item.value}, origin=${item.origin}, age=${item.age}, " +
                            "isStale=${item.isStale}, refreshing=${item.refreshing})",
                    )
                is StoreResult.Revalidated,
                is StoreResult.Error,
                -> return item
                is StoreResult.Loading -> Unit
            }
        }
    }

    // Turbine's 3s default nests inside the 25s shadow; raise the Turbine deadline above the
    // shadow so runTest provides the only effective timeout.
    private val TEST_TIMEOUT = 25.seconds
    private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

    private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
        coroutineRunTest(timeout = TEST_TIMEOUT) {
            val scope = this
            withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
        }

    // Keep Default-dispatch ordering, but let runTest provide the only timeout. Short nested
    // wall-clock deadlines are scheduler-sensitive under the broad root build graph.
    private suspend fun <T> awaitFromDefault(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            block()
        }

    private class SuspendGate {
        private val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val exited = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            try {
                released.await()
            } finally {
                exited.complete(Unit)
            }
        }

        suspend fun awaitEntered() {
            withContext(Dispatchers.Default) {
                entered.await()
            }
        }

        suspend fun awaitEnteredCausally() {
            entered.await()
        }

        suspend fun awaitExited() {
            withContext(Dispatchers.Default) {
                exited.await()
            }
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class CountingOverlay<V : Any>(
        var transform: (V?) -> V?,
        private val suppliedChanges: Flow<StoreKey>? = null,
    ) : Overlay<TestKey, V> {
        constructor(
            changes: Flow<StoreKey>,
            transform: (V?) -> V?,
        ) : this(transform, changes)

        val signals = MutableSharedFlow<StoreKey>(replay = 1)
        private val calls = Channel<V?>(Channel.UNLIMITED)
        var callCount: Int = 0
            private set

        override fun apply(
            key: TestKey,
            base: V?,
        ): V? {
            callCount += 1
            check(calls.trySend(base).isSuccess)
            return transform(base)
        }

        override val changes: Flow<StoreKey>
            get() = suppliedChanges ?: signals

        suspend fun awaitCall(): V? =
            withContext(Dispatchers.Default) {
                calls.receive()
            }

        suspend fun awaitCallValue(expected: V) {
            while (awaitCall() != expected) Unit
        }

        fun clearCalls() {
            while (calls.tryReceive().isSuccess) Unit
        }
    }

    private class ThrowingOverlay(
        private val applyFailure: Throwable?,
        override val changes: Flow<StoreKey>,
    ) : Overlay<TestKey, String> {
        override fun apply(
            key: TestKey,
            base: String?,
        ): String? {
            applyFailure?.let { throw it }
            return base
        }
    }

    private class FailingReaderSourceOfTruth(
        private val failure: Throwable,
        private val liveReaderFailure: SuspendGate? = null,
    ) : SourceOfTruth<TestKey, String> {
        private val startupCollection = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = flow {
            if (!startupCollection.complete(Unit)) liveReaderFailure?.pause()
            throw failure
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) = Unit

        override suspend fun delete(key: TestKey) = Unit

        override suspend fun deleteNamespace(namespace: org.mobilenativefoundation.store6.core.StoreNamespace) = Unit

        override suspend fun deleteAll() = Unit
    }

    /** Contract-honoring replay source with deterministic reader enrollment. */
    private class ContractSourceOfTruth<V : Any> : SourceOfTruth<TestKey, V> {
        private val rows = MutableSharedFlow<V?>(replay = 1).also { check(it.tryEmit(null)) }
        val readerStarted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<V?> = flow {
            readerStarted.complete(Unit)
            emitAll(rows)
        }

        override suspend fun write(
            key: TestKey,
            value: V,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        override suspend fun deleteNamespace(namespace: org.mobilenativefoundation.store6.core.StoreNamespace) {
            rows.emit(null)
        }

        override suspend fun deleteAll() {
            rows.emit(null)
        }
    }

    private class RecordingTelemetry : StoreTelemetry {
        val serves = mutableListOf<Origin>()
        private val serveEvents = Channel<Origin>(Channel.UNLIMITED)

        override fun onServe(
            key: StoreKey,
            origin: Origin,
        ) {
            serves += origin
            check(serveEvents.trySend(origin).isSuccess)
        }

        suspend fun awaitServe(expected: Origin? = null): Origin {
            while (true) {
                val observed =
                    withContext(Dispatchers.Default) {
                        serveEvents.receive()
                    }
                if (expected == null || observed == expected) return observed
            }
        }

        suspend fun awaitServeCausally(expected: Origin? = null): Origin {
            while (true) {
                val observed = serveEvents.receive()
                if (expected == null || observed == expected) return observed
            }
        }

        fun clear() {
            serves.clear()
            while (serveEvents.tryReceive().isSuccess) Unit
        }
    }

    private data class RefValue(
        val id: String,
        val tag: String,
    )

    private enum class ForeignOwnerOrdering {
        READER_FIRST,
        OWNER_FIRST,
    }

    private class EngineHarness<V : Any>(
        val engine: KeyEngine<TestKey, V>,
        private val job: Job,
    ) {
        fun close() {
            job.cancel()
        }
    }
}
