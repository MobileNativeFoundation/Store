@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreMeta
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationEventSurfaceTest {
    // R1-22: the non-generic sealed algebra exposes the exact ruled stable fields for both the
    // intent-scoped and client-scoped variants.
    @Test
    fun eventAlgebra_exposesIntentAndClientScopedStableFields() = runTest {
        val identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1")
        val failure =
            sanitizedMutationFailure(
                kind = MutationFailureKind.TRANSPORT,
                detail = "transport",
                message = "offline",
                occurredAtEpochMillis = 10L,
            )
        val serverMeta = EventSurfaceTestMeta(writtenAtEpochMillis = 41L, etag = "etag-server")

        val enqueued =
            MutationEnqueued(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 1L,
                clientSequence = 9L,
                mutatorId = "append",
            )
        val attempted =
            MutationAttempted(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 2L,
                generation = 1,
                attempt = 1,
            )
        val conflictObserved =
            MutationConflictObserved(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 3L,
                generation = 1,
                serverMeta = serverMeta,
            )
        val acknowledged =
            MutationAcknowledged(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 4L,
                generation = 1,
                presence = MutationPresenceState.PRESENT,
            )
        val adopted =
            MutationAdopted(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 5L,
                generation = 1,
                presence = MutationPresenceState.ABSENT,
            )
        val effectApplied =
            MutationEffectApplied(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 6L,
                generation = 1,
                effectIndex = 0,
            )
        val effectSkipped =
            MutationEffectSkipped(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 7L,
                generation = 1,
                effectIndex = 1,
            )
        val failed =
            MutationFailed(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 8L,
                generation = 1,
                state = MutationPendingState.INFLIGHT,
                failure = failure,
            )
        val parked =
            MutationParked(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 9L,
                generation = 1,
                failure = failure,
            )
        val retired =
            MutationRetired(
                mutationId = "mutation-1",
                identity = identity,
                occurredAtEpochMillis = 10L,
                generation = 1,
                retiredThroughSequence = 9L,
            )

        val intentEvents: List<MutationIntentEvent> =
            listOf(
                enqueued, attempted, conflictObserved, acknowledged, adopted,
                effectApplied, effectSkipped, failed, parked, retired,
            )
        intentEvents.forEach { event ->
            assertEquals("mutation-1", event.mutationId)
            assertSame(identity, event.identity)
        }
        assertEquals((1L..10L).toList(), intentEvents.map(MutationIntentEvent::occurredAtEpochMillis))

        assertEquals(9L, enqueued.clientSequence)
        assertEquals("append", enqueued.mutatorId)
        assertEquals(1, attempted.generation)
        assertEquals(1, attempted.attempt)
        assertSame<StoreMeta>(serverMeta, assertIs<StoreMeta>(conflictObserved.serverMeta))
        assertEquals(MutationPresenceState.PRESENT, acknowledged.presence)
        assertEquals(MutationPresenceState.ABSENT, adopted.presence)
        assertEquals(0, effectApplied.effectIndex)
        assertEquals(1, effectSkipped.effectIndex)
        assertEquals(MutationPendingState.INFLIGHT, failed.state)
        assertSame(failure, failed.failure)
        assertSame(failure, parked.failure)
        assertEquals(9L, retired.retiredThroughSequence)

        val checkpointConfirmed =
            MutationCheckpointConfirmed(
                occurredAtEpochMillis = 11L,
                clientId = "client-1",
                requestedThroughSequence = 9L,
                confirmedThroughSequence = 9L,
            )
        val checkpointFailed =
            MutationCheckpointFailed(
                occurredAtEpochMillis = 12L,
                clientId = "client-1",
                requestedThroughSequence = 9L,
                failure = failure,
            )
        assertEquals("client-1", checkpointConfirmed.clientId)
        assertEquals(9L, checkpointConfirmed.requestedThroughSequence)
        assertEquals(9L, checkpointConfirmed.confirmedThroughSequence)
        assertEquals("client-1", checkpointFailed.clientId)
        assertEquals(9L, checkpointFailed.requestedThroughSequence)
        assertSame(failure, checkpointFailed.failure)

        // Sealed exhaustiveness over the event root needs exactly the three ruled branches.
        val allEvents: List<MutationEvent> = intentEvents + checkpointConfirmed + checkpointFailed
        val described =
            allEvents.map { event ->
                when (event) {
                    is MutationIntentEvent -> "intent"
                    is MutationCheckpointConfirmed -> "checkpoint-confirmed"
                    is MutationCheckpointFailed -> "checkpoint-failed"
                }
            }
        assertEquals(List(10) { "intent" } + "checkpoint-confirmed" + "checkpoint-failed", described)
    }

    // R1-22: checkpoint events are client-scoped and never fabricate a mutation identity.
    @Test
    fun checkpointEventsCannotBeCastToIntentEvents() = runTest {
        val failure =
            sanitizedMutationFailure(
                kind = MutationFailureKind.TRANSPORT,
                detail = "transport",
                message = "offline",
                occurredAtEpochMillis = 10L,
            )
        val confirmed: MutationEvent =
            MutationCheckpointConfirmed(
                occurredAtEpochMillis = 11L,
                clientId = "client-1",
                requestedThroughSequence = 9L,
                confirmedThroughSequence = 9L,
            )
        val failed: MutationEvent =
            MutationCheckpointFailed(
                occurredAtEpochMillis = 12L,
                clientId = "client-1",
                requestedThroughSequence = 9L,
                failure = failure,
            )

        assertFalse(confirmed is MutationIntentEvent)
        assertFalse(failed is MutationIntentEvent)
        assertNull(confirmed as? MutationIntentEvent)
        assertNull(failed as? MutationIntentEvent)
    }

    // R1-22: the facade property is a read-only advisory SharedFlow backed by a non-blocking
    // bounded bus; it is never a drain, acknowledgement, retry, or settlement protocol.
    @Test
    fun events_isReadOnlyAdvisorySharedFlow_notDrainProtocol() = runTest {
        // Compile-level: the facade exposes a read-only SharedFlow<MutationEvent> property.
        val property:
            KProperty1<MutationStore<MutationsTestKey, String>, SharedFlow<MutationEvent>> =
            MutationStore<MutationsTestKey, String>::events
        assertEquals("events", property.name)

        val bus = MutationEventBus()
        // The published view is read-only, not the mutable sink.
        assertFalse(bus.events is MutableSharedFlow<*>)

        // Replay 0: history emitted before collection is never delivered to a new collector.
        repeat(3) { index -> assertTrue(bus.tryEmit(enqueuedAt(index.toLong()))) }
        bus.events.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // DROP_OLDEST with extra capacity 64: advisory emission never blocks, suspends, or
        // fails, even with no consumer — dropped telemetry never changes state.
        repeat(200) { index -> assertTrue(bus.tryEmit(enqueuedAt(index.toLong()))) }
    }
}

private class EventSurfaceTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private fun enqueuedAt(sequence: Long): MutationEnqueued =
    MutationEnqueued(
        mutationId = "mutation-$sequence",
        identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1"),
        occurredAtEpochMillis = sequence,
        clientSequence = sequence,
        mutatorId = "append",
    )

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
