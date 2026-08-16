@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationEventsTest {
    @Test
    fun enqueueAttemptConflictAckAdoptEffectAndRetire_emitInCausalOrder() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val backend = FakeBackend()
        val handle = EventWriteHandle()
        val source = MutationsTestKey("events-causal-source")
        val target = MutationsTestKey("events-causal-target")
        backend.pushBehavior = { _, value ->
            if (backend.receivedPushes.size == 1) {
                throw eventConflict(EventMeta(41L, "events-conflict"))
            }
            MutationPresentAck(
                authoritative = value,
                etag = "events-acked",
                canonicalKey = target,
            )
        }
        val engine =
            openEventEngine(
                storage = storage,
                mutations = mutations,
                backend = backend,
                handle = handle,
                conflicts =
                    MutationConflictRegistration(
                        precondition = null,
                        merge = { _, mine, _ -> MutationConflictResolution.Retry(mine) },
                    ),
            )
        val observed = collectEvents(engine)

        val mutationId = engine.mutate(source, mutations.set, "mine")
        engine.drain(source)
        engine.drain(source)
        runCurrent()

        assertEquals(
            listOf(
                "enqueued",
                "attempted",
                "conflict",
                "attempted",
                "acknowledged",
                "adopted",
                "effect-applied",
                "retired",
            ),
            observed.map(MutationEvent::eventName),
        )
        assertEventTimes(observed)
        val intentEvents = observed.filterIsInstance<MutationIntentEvent>()
        assertEquals(8, intentEvents.size)
        intentEvents.forEach { event ->
            assertEquals(mutationId, event.mutationId)
            assertIdentity(source.identity(), event.identity)
        }
        val enqueued = assertIs<MutationEnqueued>(intentEvents[0])
        assertEquals(1L, enqueued.clientSequence)
        assertEquals("events-set", enqueued.mutatorId)
        val attempts = intentEvents.filterIsInstance<MutationAttempted>()
        assertEquals(listOf(1 to 1, 2 to 1), attempts.map { it.generation to it.attempt })
        val conflict = assertIs<MutationConflictObserved>(intentEvents[2])
        assertEquals(1, conflict.generation)
        assertEquals(41L, conflict.serverMeta?.writtenAtEpochMillis)
        assertEquals("events-conflict", conflict.serverMeta?.etag)
        val acknowledged = assertIs<MutationAcknowledged>(intentEvents[4])
        assertEquals(2, acknowledged.generation)
        assertEquals(MutationPresenceState.PRESENT, acknowledged.presence)
        val adopted = assertIs<MutationAdopted>(intentEvents[5])
        assertEquals(2, adopted.generation)
        assertEquals(MutationPresenceState.PRESENT, adopted.presence)
        val effect = assertIs<MutationEffectApplied>(intentEvents[6])
        assertEquals(2, effect.generation)
        assertEquals(0, effect.effectIndex)
        val retired = assertIs<MutationRetired>(intentEvents[7])
        assertEquals(2, retired.generation)
        assertEquals(1L, retired.retiredThroughSequence)
    }

    @Test
    fun activeFailurePrecedesLaterRetryEvent() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val backend = FakeBackend()
        var failNextPush = true
        backend.pushBehavior = { _, value ->
            if (failNextPush) {
                failNextPush = false
                throw IllegalStateException("events transport offline")
            }
            MutationPresentAck(value, "events-retry-acked", null)
        }
        val engine = openEventEngine(storage, mutations, backend)
        val observed = collectEvents(engine)
        val key = MutationsTestKey("events-active-failure")

        val mutationId = engine.mutate(key, mutations.set, "mine")
        engine.drain(key)
        runCurrent()

        engine.drain(key)
        runCurrent()

        assertEquals(
            listOf(
                "enqueued",
                "attempted",
                "failed",
                "attempted",
                "acknowledged",
                "adopted",
                "effect-applied",
                "retired",
            ),
            observed.map(MutationEvent::eventName),
        )
        assertEventTimes(observed)
        val failure = assertIs<MutationFailed>(observed[2])
        assertEquals(mutationId, failure.mutationId)
        assertIdentity(key.identity(), failure.identity)
        assertEquals(1, failure.generation)
        assertEquals(MutationPendingState.PENDING, failure.state)
        assertEquals(MutationFailureKind.TRANSPORT, failure.failure.kind)
        assertEquals("push-failed", failure.failure.detail)
        assertEquals("events transport offline", failure.failure.message)
        assertEquals(EVENT_TIME, failure.failure.occurredAtEpochMillis)
        observed.filterIsInstance<MutationIntentEvent>().forEach { event ->
            assertEquals(mutationId, event.mutationId)
            assertIdentity(key.identity(), event.identity)
        }
        val attempts = observed.filterIsInstance<MutationAttempted>()
        assertEquals(listOf(1, 1), attempts.map { it.generation })
        assertEquals(listOf(1, 2), attempts.map { it.attempt })
        assertTrue(observed.indexOf(failure) < observed.indexOf(attempts.last()))
        val acknowledged = assertIs<MutationAcknowledged>(observed[4])
        assertEquals(1, acknowledged.generation)
        assertEquals(MutationPresenceState.PRESENT, acknowledged.presence)
        val adopted = assertIs<MutationAdopted>(observed[5])
        assertEquals(1, adopted.generation)
        assertEquals(MutationPresenceState.PRESENT, adopted.presence)
        val effect = assertIs<MutationEffectApplied>(observed[6])
        assertEquals(1, effect.generation)
        assertEquals(0, effect.effectIndex)
        val retired = assertIs<MutationRetired>(observed[7])
        assertEquals(1, retired.generation)
        assertEquals(1L, retired.retiredThroughSequence)
    }

    @Test
    fun appliedAndSkippedEffectsHaveDistinctEvents() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val backend = FakeBackend()
        val appliedKey = MutationsTestKey("events-effect-applied")
        val skippedKey = MutationsTestKey("events-effect-skipped")
        backend.pushBehavior = { key, value ->
            if (key.identity() == skippedKey.identity()) {
                throw eventConflict(EventMeta(52L, "server-wins"))
            }
            MutationPresentAck(value, "effect-applied", null)
        }
        val engine = openEventEngine(storage, mutations, backend)
        val observed = collectEvents(engine)

        val appliedId = engine.mutate(appliedKey, mutations.set, "applied")
        engine.drain(appliedKey)
        val skippedId = engine.mutate(skippedKey, mutations.set, "skipped")
        engine.drain(skippedKey)
        engine.drain(skippedKey)
        runCurrent()

        assertEquals(
            listOf(
                "enqueued",
                "attempted",
                "acknowledged",
                "adopted",
                "effect-applied",
                "retired",
                "enqueued",
                "attempted",
                "conflict",
                "effect-skipped",
                "retired",
            ),
            observed.map(MutationEvent::eventName),
        )
        assertEventTimes(observed)
        observed.subList(0, 6).forEach { event ->
            val intent = assertIs<MutationIntentEvent>(event)
            assertEquals(appliedId, intent.mutationId)
            assertIdentity(appliedKey.identity(), intent.identity)
        }
        observed.subList(6, 11).forEach { event ->
            val intent = assertIs<MutationIntentEvent>(event)
            assertEquals(skippedId, intent.mutationId)
            assertIdentity(skippedKey.identity(), intent.identity)
        }
        val applied = assertIs<MutationEffectApplied>(observed[4])
        assertEquals(appliedId, applied.mutationId)
        assertIdentity(appliedKey.identity(), applied.identity)
        assertEquals(0, applied.effectIndex)
        assertEquals(1, applied.generation)
        val skipped = assertIs<MutationEffectSkipped>(observed[9])
        assertEquals(skippedId, skipped.mutationId)
        assertIdentity(skippedKey.identity(), skipped.identity)
        assertEquals(0, skipped.effectIndex)
        assertEquals(1, skipped.generation)
        val conflict = assertIs<MutationConflictObserved>(observed[8])
        assertEquals(skippedId, conflict.mutationId)
        assertIdentity(skippedKey.identity(), conflict.identity)
        assertEquals(1, conflict.generation)
        val appliedRetired = assertIs<MutationRetired>(observed[5])
        assertEquals(appliedId, appliedRetired.mutationId)
        assertEquals(1L, appliedRetired.retiredThroughSequence)
        val skippedRetired = assertIs<MutationRetired>(observed[10])
        assertEquals(skippedId, skippedRetired.mutationId)
        assertEquals(2L, skippedRetired.retiredThroughSequence)
    }

    @Test
    fun parkEmitsNormalizedFailureWithoutRawThrowable() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val engine = openEventEngine(storage, mutations, FakeBackend())
        val observed = collectEvents(engine)
        val key = MutationsTestKey("events-park")

        val mutationId = engine.mutate(key, mutations.park, "ignored")
        engine.drain(key)
        runCurrent()

        assertEquals(listOf("enqueued", "parked"), observed.map(MutationEvent::eventName))
        assertEventTimes(observed)
        observed.forEach { event ->
            val intent = assertIs<MutationIntentEvent>(event)
            assertEquals(mutationId, intent.mutationId)
            assertIdentity(key.identity(), intent.identity)
        }
        val parked = assertIs<MutationParked>(observed[1])
        assertEquals(mutationId, parked.mutationId)
        assertIdentity(key.identity(), parked.identity)
        assertEquals(0, parked.generation)
        assertEquals(MutationFailureKind.PROJECTION, parked.failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_PROJECTION_THROW, parked.failure.detail)
        assertEquals("events projection exploded", parked.failure.message)
        assertEquals(EVENT_TIME, parked.failure.occurredAtEpochMillis)
        assertFalse(parked.failure.message.contains("raw.Stack"))
        assertFalse(parked.failure.message.contains("IllegalStateException"))
        assertTrue(observed.none { it is MutationFailed })
        val deadLetter = engine.deadLetters().single()
        assertEquals(mutationId, deadLetter.mutationId)
        assertEquals(parked.generation, deadLetter.generation)
        assertEquals(parked.failure.kind, deadLetter.failure.kind)
        assertEquals(parked.failure.detail, deadLetter.failure.detail)
        assertEquals(parked.failure.message, deadLetter.failure.message)
        assertEquals(
            parked.failure.occurredAtEpochMillis,
            deadLetter.failure.occurredAtEpochMillis,
        )
    }

    @Test
    fun checkpointFailureHasClientScopeWithoutFabricatedMutationIdentity() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val backend = FakeBackend()
        val engine = openEventEngine(storage, mutations, backend)
        backend.retireBehavior = {
            throw IllegalStateException("events checkpoint unavailable")
        }
        val observed = collectEvents(engine)
        val key = MutationsTestKey("events-checkpoint-failure")

        val mutationId = engine.mutate(key, mutations.set, "mine")
        engine.drain(key)
        runCurrent()

        assertEquals(
            listOf(
                "enqueued",
                "attempted",
                "acknowledged",
                "adopted",
                "effect-applied",
                "retired",
                "checkpoint-failed",
            ),
            observed.map(MutationEvent::eventName),
        )
        assertEventTimes(observed)
        observed.subList(0, 6).forEach { event ->
            val intent = assertIs<MutationIntentEvent>(event)
            assertEquals(mutationId, intent.mutationId)
            assertIdentity(key.identity(), intent.identity)
        }
        val failed = assertIs<MutationCheckpointFailed>(observed[6])
        val checkpointEvent: MutationEvent = failed
        assertFalse(checkpointEvent is MutationIntentEvent)
        assertEquals(EVENT_CLIENT_ID, failed.clientId)
        assertEquals(1L, failed.requestedThroughSequence)
        assertEquals(MutationFailureKind.TRANSPORT, failed.failure.kind)
        assertEquals("retire-failed", failed.failure.detail)
        assertEquals("events checkpoint unavailable", failed.failure.message)
        assertEquals(EVENT_TIME, failed.failure.occurredAtEpochMillis)
        assertTrue(storage.transaction { it.failures(EVENT_CLIENT_ID).isEmpty() })
        assertTrue(observed.none { it is MutationFailed })
        val client = storage.transaction { requireNotNull(it.client(EVENT_CLIENT_ID)) }
        assertEquals(1L, client.retiredThroughSequence)
        assertEquals(0L, client.serverConfirmedRetiredThroughSequence)
    }

    @Test
    fun restartDoesNotDuplicateCompletedLifecycleEvents() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("events-completed-restart")
        val first = openEventEngine(storage, mutations, backend)
        val firstEvents = collectEvents(first)

        val mutationId = first.mutate(key, mutations.set, "complete")
        first.drain(key)
        runCurrent()

        val reopened = openEventEngine(storage, mutations, backend, EventWriteHandle())
        val restartedEvents = collectEvents(reopened)
        reopened.ensureHydrated()
        runCurrent()
        assertEquals(
            emptyList<String>(),
            restartedEvents.map(MutationEvent::eventName),
            "hydration must emit no lifecycle history",
        )

        reopened.drain(key)
        reopened.drain()
        runCurrent()
        assertEquals(
            listOf(
                "enqueued",
                "attempted",
                "acknowledged",
                "adopted",
                "effect-applied",
                "retired",
            ),
            firstEvents.map(MutationEvent::eventName),
        )
        assertEventTimes(firstEvents)
        firstEvents.filterIsInstance<MutationIntentEvent>().forEach { event ->
            assertEquals(mutationId, event.mutationId)
            assertIdentity(key.identity(), event.identity)
        }
        assertEquals(
            emptyList<String>(),
            restartedEvents.map(MutationEvent::eventName),
            "completed transitions must not re-emit after restart",
        )
        assertEquals(1, backend.receivedPushes.size)
        assertEquals(
            MutationExecutionPhase.RETIRED,
            storage.transaction { it.executions(EVENT_CLIENT_ID).single().phase },
        )
    }

    @Test
    fun restartResumedTransitions_emitTheirLifecycleEventsNormally() = runTest {
        val delegate = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(delegate)
        val mutations = EventMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("events-acked-restart")
        val first = openEventEngine(storage, mutations, backend)
        val firstEvents = collectEvents(first)
        val mutationId = first.mutate(key, mutations.set, "resume")
        storage.armKillAfterCommit(JournalFailPointBoundary.ACK_RECEIPT)

        val death = assertFailsWith<FailPointProcessDeathException> { first.drain(key) }
        assertTrue(death.committed)
        runCurrent()
        assertEquals(1, backend.receivedPushes.size)
        assertEquals(
            MutationExecutionPhase.ACKED,
            storage.transaction { it.executions(EVENT_CLIENT_ID).single().phase },
        )

        val reopened = openEventEngine(storage, mutations, backend, EventWriteHandle())
        val observed = collectEvents(reopened)
        reopened.ensureHydrated()
        runCurrent()
        assertEquals(
            emptyList<String>(),
            observed.map(MutationEvent::eventName),
            "ACKED hydration must not emit",
        )

        reopened.drain(key)
        runCurrent()

        assertEquals(
            listOf("enqueued", "attempted"),
            firstEvents.map(MutationEvent::eventName),
        )
        assertEventTimes(firstEvents)
        firstEvents.filterIsInstance<MutationIntentEvent>().forEach { event ->
            assertEquals(mutationId, event.mutationId)
            assertIdentity(key.identity(), event.identity)
        }
        val firstAttempt = assertIs<MutationAttempted>(firstEvents[1])
        assertEquals(1, firstAttempt.generation)
        assertEquals(1, firstAttempt.attempt)
        assertEquals(
            listOf("adopted", "effect-applied", "retired"),
            observed.map(MutationEvent::eventName),
        )
        assertEventTimes(observed)
        observed.filterIsInstance<MutationIntentEvent>().forEach { event ->
            assertEquals(mutationId, event.mutationId)
            assertIdentity(key.identity(), event.identity)
        }
        val adopted = assertIs<MutationAdopted>(observed[0])
        assertEquals(1, adopted.generation)
        assertEquals(MutationPresenceState.PRESENT, adopted.presence)
        val effect = assertIs<MutationEffectApplied>(observed[1])
        assertEquals(1, effect.generation)
        assertEquals(0, effect.effectIndex)
        val retired = assertIs<MutationRetired>(observed[2])
        assertEquals(1, retired.generation)
        assertEquals(1L, retired.retiredThroughSequence)
        assertEquals(1, backend.receivedPushes.size)
        assertEquals(
            MutationExecutionPhase.RETIRED,
            storage.transaction { it.executions(EVENT_CLIENT_ID).single().phase },
        )
    }

    @Test
    fun replayedInflightSend_reEmitsSameAttemptOrdinal() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = EventMutations()
        val cancellingBackend = FakeBackend()
        cancellingBackend.pushBehavior = { _, _ ->
            throw CancellationException("events transport cancelled")
        }
        val key = MutationsTestKey("events-inflight-replay")
        val first = openEventEngine(storage, mutations, cancellingBackend)
        val firstEvents = collectEvents(first)
        val mutationId = first.mutate(key, mutations.set, "replay")

        assertFailsWith<CancellationException> { first.drain(key) }
        runCurrent()

        assertEquals(MutationPendingState.INFLIGHT, first.pending(key).single().state)
        assertEquals(0, first.pending(key).single().attempt)

        val replayBackend = FakeBackend()
        val reopened = openEventEngine(storage, mutations, replayBackend, EventWriteHandle())
        val replayEvents = collectEvents(reopened)
        reopened.ensureHydrated()
        runCurrent()
        assertEquals(
            emptyList<String>(),
            replayEvents.map(MutationEvent::eventName),
            "INFLIGHT hydration must not emit",
        )

        reopened.drain(key)
        runCurrent()

        assertEquals(listOf("enqueued", "attempted"), firstEvents.map(MutationEvent::eventName))
        assertEventTimes(firstEvents)
        val firstAttempt = assertIs<MutationAttempted>(firstEvents.last())
        assertEquals(mutationId, firstAttempt.mutationId)
        assertIdentity(key.identity(), firstAttempt.identity)
        assertEquals(1, firstAttempt.generation)
        assertEquals(1, firstAttempt.attempt)
        assertEquals(
            listOf("attempted", "acknowledged", "adopted", "effect-applied", "retired"),
            replayEvents.map(MutationEvent::eventName),
        )
        assertEventTimes(replayEvents)
        replayEvents.forEach { event ->
            val intent = assertIs<MutationIntentEvent>(event)
            assertEquals(mutationId, intent.mutationId)
            assertIdentity(key.identity(), intent.identity)
        }
        val replayAttempt = assertIs<MutationAttempted>(replayEvents.first())
        assertEquals(mutationId, replayAttempt.mutationId)
        assertEquals(firstAttempt.generation, replayAttempt.generation)
        assertEquals(firstAttempt.attempt, replayAttempt.attempt)
        assertIdentity(firstAttempt.identity, replayAttempt.identity)
        assertEquals(1, replayBackend.receivedPushes.size)
        val firstPush = cancellingBackend.receivedPushes.single()
        val replayPush = replayBackend.receivedPushes.single()
        assertEquals(firstPush.clientId, replayPush.clientId)
        assertEquals(firstPush.clientSequence, replayPush.clientSequence)
        assertEquals(firstPush.retiredThroughSequence, replayPush.retiredThroughSequence)
        assertEquals(firstPush.mutationId, replayPush.mutationId)
        assertEquals(firstPush.generation, replayPush.generation)
        assertEquals(firstPush.idempotencyKey, replayPush.idempotencyKey)
        assertEquals(firstPush.valueCodecVersion, replayPush.valueCodecVersion)
        assertIdentity(firstPush.identity, replayPush.identity)
        val acknowledged = assertIs<MutationAcknowledged>(replayEvents[1])
        assertEquals(1, acknowledged.generation)
        assertEquals(MutationPresenceState.PRESENT, acknowledged.presence)
        val adopted = assertIs<MutationAdopted>(replayEvents[2])
        assertEquals(1, adopted.generation)
        assertEquals(MutationPresenceState.PRESENT, adopted.presence)
        val effect = assertIs<MutationEffectApplied>(replayEvents[3])
        assertEquals(1, effect.generation)
        assertEquals(0, effect.effectIndex)
        val retired = assertIs<MutationRetired>(replayEvents[4])
        assertEquals(1, retired.generation)
        assertEquals(1L, retired.retiredThroughSequence)
    }
}

private const val EVENT_CLIENT_ID: String = "client-0"
private const val EVENT_TIME: Long = 1_000L

private class EventMutations {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>
    lateinit var park: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                mutator(
                    id = "events-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = { key, _ ->
                        StaleSet(keys = setOf(key), namespaces = emptySet())
                    },
                ) { _, value -> MutationPresence.Present(value) }
            park =
                mutator(
                    id = "events-park",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, _ ->
                    throw IllegalStateException("events projection exploded\nat raw.Stack")
                }
        }
}

private class EventWriteHandle : StoreWriteHandle<MutationsTestKey, String> {
    private val values = mutableMapOf<KeyIdentity, String>()
    val staleKeys: MutableList<KeyIdentity> = mutableListOf()

    fun read(key: MutationsTestKey): String? = values[key.identity()] ?: "base"

    fun clear(key: MutationsTestKey) {
        values.remove(key.identity())
    }

    override suspend fun apply(key: MutationsTestKey, value: String) {
        values[key.identity()] = value
    }

    override suspend fun markStale(key: MutationsTestKey) {
        staleKeys += key.identity()
    }

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private fun openEventEngine(
    storage: MutationJournalStorage,
    mutations: EventMutations,
    backend: FakeBackend,
    handle: EventWriteHandle = EventWriteHandle(),
    conflicts: MutationConflictRegistration<MutationsTestKey, String>? = null,
    clock: TestWallClock = TestWallClock(EVENT_TIME),
): MutationEngine<MutationsTestKey, String> {
    backend.retireBehavior = { MutationRetirementAck(confirmedThroughSequence = 0L) }
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = EVENT_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        conflicts = conflicts,
        baseReader = handle::read,
        absentAdoption = { key -> handle.clear(key) },
        wallClock = clock,
        clientId = EVENT_CLIENT_ID,
    ).also { it.bind(handle) }
}

private fun TestScope.collectEvents(
    engine: MutationEngine<MutationsTestKey, String>,
): MutableList<MutationEvent> {
    val observed = mutableListOf<MutationEvent>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.eventBus.events.collect { event -> observed += event }
    }
    return observed
}

private fun MutationEvent.eventName(): String =
    when (this) {
        is MutationEnqueued -> "enqueued"
        is MutationAttempted -> "attempted"
        is MutationConflictObserved -> "conflict"
        is MutationAcknowledged -> "acknowledged"
        is MutationAdopted -> "adopted"
        is MutationEffectApplied -> "effect-applied"
        is MutationEffectSkipped -> "effect-skipped"
        is MutationFailed -> "failed"
        is MutationParked -> "parked"
        is MutationRetired -> "retired"
        is MutationCheckpointConfirmed -> "checkpoint-confirmed"
        is MutationCheckpointFailed -> "checkpoint-failed"
    }

private fun assertIdentity(
    expected: KeyIdentity,
    actual: MutationKeyIdentity,
) {
    assertEquals(expected.namespace, actual.namespace)
    assertEquals(expected.canonicalId, actual.canonicalId)
}

private fun assertIdentity(
    expected: MutationKeyIdentity,
    actual: MutationKeyIdentity,
) {
    assertEquals(expected.namespace, actual.namespace)
    assertEquals(expected.canonicalId, actual.canonicalId)
}

private fun assertEventTimes(events: List<MutationEvent>) {
    events.forEach { event ->
        assertEquals(EVENT_TIME, event.occurredAtEpochMillis)
        val failure =
            when (event) {
                is MutationFailed -> event.failure
                is MutationParked -> event.failure
                is MutationCheckpointFailed -> event.failure
                else -> null
            }
        if (failure != null) {
            assertEquals(EVENT_TIME, failure.occurredAtEpochMillis)
            assertTrue(failure.detail.encodeToByteArray().size <= MUTATION_FAILURE_DETAIL_MAX_UTF8_BYTES)
            assertTrue(failure.message.encodeToByteArray().size <= MUTATION_FAILURE_MESSAGE_MAX_UTF8_BYTES)
        }
    }
}

private fun eventConflict(meta: StoreMeta?): StoreException =
    StoreResults.exception(
        StoreResults.conflict(meta, "events conflict"),
        IllegalStateException("events conflict cause"),
    )

private class EventMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
