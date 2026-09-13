@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class RunActivationTest {
    @Test
    fun clearedCancelsTrackedActivation() = runTest {
        val harness = RegisteredHarness()
        try {
            harness.store.mutate(DrainTestKey("cleared"), harness.fixture.appendRef, "+done")

            harness.coordinator.events.test {
                assertIs<DrainPassOutcome.Cleared>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )

                assertIs<DrainActivationStarted>(awaitItem())
                assertIs<DrainActivationCancelled>(awaitItem())
                val completed = assertIs<DrainPassCompleted>(awaitItem())
                assertEquals(0, completed.pendingIntents)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                listOf(
                    "validate(network=true, charging=false)",
                    "schedule(users, 1h)",
                    "cancel(users)",
                ),
                harness.scheduler.log,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun transportFailureSchedulesBackoffDelay() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.offline = true
        try {
            harness.store.mutate(DrainTestKey("offline"), harness.fixture.appendRef, "+pending")

            val outcome =
                assertIs<DrainPassOutcome.Remaining>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )

            assertEquals(1, outcome.pendingIntents)
            assertEquals(30.seconds, outcome.scheduledDelay)
            assertEquals(
                listOf(1.hours, 30.seconds),
                harness.scheduler.scheduled.map(DrainRequest::earliestDelay),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun passRespectsEngineGate_noRetryStorm() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.offline = true
        try {
            harness.store.mutate(DrainTestKey("gated"), harness.fixture.appendRef, "+pending")
            assertIs<DrainPassOutcome.Remaining>(
                harness.scheduler.fireActivation(STORE_NAME),
            )
            val receivedAfterFirst = harness.fixture.backend.receivedPushes.size
            val attemptAfterFirst = harness.store.pendingWrites().single().attempt
            harness.fixture.nowMillis = -1L

            val second =
                assertIs<DrainPassOutcome.Remaining>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )

            assertEquals(receivedAfterFirst, harness.fixture.backend.receivedPushes.size)
            assertEquals(attemptAfterFirst, harness.store.pendingWrites().single().attempt)
            assertEquals(1, second.pendingIntents)
            assertEquals(30.seconds, second.scheduledDelay)
        } finally {
            harness.close()
        }
    }

    @Test
    fun postAckAdoptionFailureDerivesZeroThenEscalates() = runTest {
        val harness = RegisteredHarness(sourceOfTruth = nonProgressingPostAckSourceOfTruth())
        try {
            harness.store.mutate(DrainTestKey("adopting"), harness.fixture.appendRef, "+pending")

            val first =
                assertIs<DrainPassOutcome.Remaining>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )
            val second =
                assertIs<DrainPassOutcome.Remaining>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )

            assertEquals(Duration.ZERO, first.scheduledDelay)
            assertEquals(30.seconds, second.scheduledDelay)
        } finally {
            harness.close()
        }
    }

    @Test
    fun checkpointFailureAloneKeepsRemaining() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.retireBehavior = { error("retirement unavailable") }
        try {
            harness.store.mutate(DrainTestKey("checkpoint"), harness.fixture.appendRef, "+done")

            harness.coordinator.events.test {
                val outcome =
                    assertIs<DrainPassOutcome.Remaining>(
                        harness.scheduler.fireActivation(STORE_NAME),
                    )

                assertEquals(0, outcome.pendingIntents)
                assertEquals(30.seconds, outcome.scheduledDelay)
                assertIs<DrainActivationStarted>(awaitItem())
                assertIs<DrainActivationScheduled>(awaitItem())
                val completed = assertIs<DrainPassCompleted>(awaitItem())
                assertEquals(0, completed.pendingIntents)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun parkedOnlyYieldsCleared() = runTest {
        val fixture = ParkedFixture()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        coordinator.register(STORE_NAME, fixture.store)
        try {
            fixture.store.mutate(DrainTestKey("parked"), fixture.hostileRef, "ignored")

            coordinator.events.test {
                assertIs<DrainPassOutcome.Cleared>(scheduler.fireActivation(STORE_NAME))

                assertIs<DrainActivationStarted>(awaitItem())
                assertIs<DrainActivationCancelled>(awaitItem())
                val completed = assertIs<DrainPassCompleted>(awaitItem())
                assertEquals(0, completed.pendingIntents)
                assertTrue(completed.deadLetters > 0)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            coordinator.close()
            fixture.store.close()
        }
    }

    @Test
    fun safetyActivationPersistsBeforeDrainAndIsReplaced() = runTest {
        val harness = RegisteredHarness()
        val gate = CompletableDeferred<Unit>()
        harness.fixture.backend.offline = true
        harness.fixture.backend.pushGate = gate
        try {
            harness.store.mutate(DrainTestKey("safety-order"), harness.fixture.appendRef, "+pending")

            val activation = async { harness.scheduler.fireActivation(STORE_NAME) }
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()

            assertEquals(1, harness.fixture.backend.maxConcurrentPushes)
            assertEquals(
                listOf(1.hours),
                harness.scheduler.scheduled.map(DrainRequest::earliestDelay),
            )

            gate.complete(Unit)
            val outcome = assertIs<DrainPassOutcome.Remaining>(activation.await())
            assertEquals(30.seconds, outcome.scheduledDelay)
            assertEquals(
                listOf(1.hours, 30.seconds),
                harness.scheduler.scheduled.map(DrainRequest::earliestDelay),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun safetyPersistFailureStillRunsPass() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.offline = true
        harness.scheduler.scheduleThrowsOnCall = 1
        harness.scheduler.scheduleThrows = { IllegalStateException("safety persist failed") }
        try {
            harness.store.mutate(DrainTestKey("safety-failure"), harness.fixture.appendRef, "+pending")

            harness.coordinator.events.test {
                val outcome =
                    assertIs<DrainPassOutcome.Remaining>(
                        harness.scheduler.fireActivation(STORE_NAME),
                    )

                assertEquals(30.seconds, outcome.scheduledDelay)
                assertEquals(1, harness.store.pendingWrites().single().attempt)
                val failed = assertIs<DrainScheduleFailed>(awaitItem())
                assertEquals("safety persist failed", failed.message)
                assertIs<DrainActivationStarted>(awaitItem())
                assertIs<DrainActivationScheduled>(awaitItem())
                assertIs<DrainPassCompleted>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun followUpScheduleFailureReturnsNullDelay() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.offline = true
        harness.scheduler.scheduleThrowsOnCall = 2
        harness.scheduler.scheduleThrows = { IllegalStateException("follow-up failed") }
        try {
            harness.store.mutate(DrainTestKey("follow-up-failure"), harness.fixture.appendRef, "+pending")

            harness.coordinator.events.test {
                val outcome =
                    assertIs<DrainPassOutcome.Remaining>(
                        harness.scheduler.fireActivation(STORE_NAME),
                    )

                assertEquals(1, outcome.pendingIntents)
                assertEquals(null, outcome.scheduledDelay)
                assertIs<DrainActivationStarted>(awaitItem())
                val failed = assertIs<DrainScheduleFailed>(awaitItem())
                assertEquals("follow-up failed", failed.message)
                assertIs<DrainPassCompleted>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancellationMidPassLeavesSafetyTracked() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.backend.pushGate = CompletableDeferred()
        try {
            harness.store.mutate(DrainTestKey("cancelled"), harness.fixture.appendRef, "+pending")

            val activation = async { harness.scheduler.fireActivation(STORE_NAME) }
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()
            activation.cancelAndJoin()

            assertEquals(1.hours, harness.scheduler.scheduled.last().earliestDelay)
            assertEquals(1, harness.scheduler.scheduled.size)
            assertEquals(
                org.mobilenativefoundation.store6.mutations.MutationPendingState.INFLIGHT,
                harness.store.pendingWrites().single().state,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun unregisterMidPassSuppressesFollowUp() = runTest {
        val harness = RegisteredHarness()
        val gate = CompletableDeferred<Unit>()
        harness.fixture.backend.offline = true
        harness.fixture.backend.pushGate = gate
        try {
            harness.store.mutate(DrainTestKey("unregister"), harness.fixture.appendRef, "+pending")
            val activation = async { harness.scheduler.fireActivation(STORE_NAME) }
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()

            harness.coordinator.unregister(STORE_NAME)
            gate.complete(Unit)
            val outcome = assertIs<DrainPassOutcome.Remaining>(activation.await())

            assertEquals(null, outcome.scheduledDelay)
            assertEquals(1, harness.scheduler.scheduled.size)
            assertEquals(
                listOf(
                    "validate(network=true, charging=false)",
                    "schedule(users, 1h)",
                    "cancel(users)",
                ),
                harness.scheduler.log,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun closedStoreUnknownNameAndPostCloseYieldUnavailable() = runTest {
        val harness = RegisteredHarness()
        try {
            harness.store.close()

            val closed =
                assertIs<DrainPassOutcome.Unavailable>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )
            val unknown =
                assertIs<DrainPassOutcome.Unavailable>(
                    harness.coordinator.runActivation("unknown"),
                )
            val cancellationsBeforeClose = harness.scheduler.cancelled.size
            harness.coordinator.close()
            val postClose =
                assertIs<DrainPassOutcome.Unavailable>(
                    harness.coordinator.runActivation(STORE_NAME),
                )

            assertTrue(closed.reason.startsWith("store closed:"))
            assertEquals("name not registered", unknown.reason)
            assertEquals("coordinator closed", postClose.reason)
            assertEquals(cancellationsBeforeClose, harness.scheduler.cancelled.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun concurrentActivationsSerializePerName() = runTest {
        val harness = RegisteredHarness()
        val gate = CompletableDeferred<Unit>()
        harness.fixture.backend.pushGate = gate
        try {
            harness.store.mutate(DrainTestKey("concurrent"), harness.fixture.appendRef, "+done")

            val first = async { harness.scheduler.fireActivation(STORE_NAME) }
            val second = async { harness.scheduler.fireActivation(STORE_NAME) }
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()
            assertEquals(1, harness.fixture.backend.maxConcurrentPushes)

            gate.complete(Unit)
            assertIs<DrainPassOutcome.Cleared>(first.await())
            assertIs<DrainPassOutcome.Cleared>(second.await())
            assertEquals(1, harness.fixture.backend.maxConcurrentPushes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun reconcileRunsUnconditionalPassAndSkipsBusyStores() = runTest {
        val fixtureA = DrainFixture()
        val fixtureB = DrainFixture()
        val fixtureC = DrainFixture()
        val storeA = fixtureA.openStore()
        val storeB = fixtureB.openStore()
        val storeC = fixtureC.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixtureA.clock)
        coordinator.register("a", storeA)
        coordinator.register("b", storeB)
        coordinator.register("c", storeC)
        var retireCalls = 0
        fixtureC.backend.retireBehavior = {
            retireCalls += 1
            if (retireCalls == 1) {
                error("checkpoint unavailable")
            }
            MutationRetirementAck(confirmedThroughSequence = 1L)
        }
        val gate = CompletableDeferred<Unit>()
        fixtureA.backend.pushGate = gate
        try {
            storeC.mutate(DrainTestKey("checkpoint"), fixtureC.appendRef, "+done")
            assertIs<DrainPassOutcome.Remaining>(scheduler.fireActivation("c"))
            assertEquals(1, retireCalls)

            storeA.mutate(DrainTestKey("busy"), fixtureA.appendRef, "+done")
            storeB.mutate(DrainTestKey("ready"), fixtureB.appendRef, "+done")
            val aPass = async { scheduler.fireActivation("a") }
            fixtureA.backend.pushEntered.awaitFromDefaultContext()
            val aSchedulesBeforeReconcile =
                scheduler.scheduled.count { request -> request.storeName == "a" }

            coordinator.reconcile()

            assertEquals(
                aSchedulesBeforeReconcile,
                scheduler.scheduled.count { request -> request.storeName == "a" },
            )
            assertEquals(1, fixtureA.backend.maxConcurrentPushes)
            assertEquals(emptyList(), storeB.pendingWrites())
            assertTrue(retireCalls >= 2)

            gate.complete(Unit)
            assertIs<DrainPassOutcome.Cleared>(aPass.await())
            assertEquals(1, fixtureA.backend.receivedPushes.size)
        } finally {
            coordinator.close()
            storeA.close()
            storeB.close()
            storeC.close()
        }
    }

    @Test
    fun coordinatorRecreationResetsEscalation() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        val firstScheduler = RecordingDrainScheduler()
        val firstCoordinator = mutationDrainCoordinator(firstScheduler, fixture.clock)
        firstCoordinator.register(STORE_NAME, store)
        try {
            store.mutate(DrainTestKey("restart"), fixture.appendRef, "+pending")
            assertIs<DrainPassOutcome.Remaining>(firstScheduler.fireActivation(STORE_NAME))
            fixture.nowMillis = -1L
            assertIs<DrainPassOutcome.Remaining>(firstScheduler.fireActivation(STORE_NAME))
            val escalated =
                assertIs<DrainPassOutcome.Remaining>(
                    firstScheduler.fireActivation(STORE_NAME),
                )
            assertEquals(60.seconds, escalated.scheduledDelay)
            firstCoordinator.close()

            val secondScheduler = RecordingDrainScheduler()
            val secondCoordinator = mutationDrainCoordinator(secondScheduler, fixture.clock)
            secondCoordinator.register(STORE_NAME, store)
            try {
                val reset =
                    assertIs<DrainPassOutcome.Remaining>(
                        secondScheduler.fireActivation(STORE_NAME),
                    )
                assertEquals(30.seconds, reset.scheduledDelay)
            } finally {
                secondCoordinator.close()
            }
        } finally {
            firstCoordinator.close()
            store.close()
        }
    }

    @Test
    fun eventsCarryFields() = runTest {
        val harness = RegisteredHarness()
        harness.fixture.nowMillis = 42_424L
        harness.fixture.backend.offline = true
        harness.scheduler.scheduleThrowsOnCall = 1
        harness.scheduler.scheduleThrows = { IllegalStateException("safety failed") }
        try {
            harness.store.mutate(DrainTestKey("events"), harness.fixture.appendRef, "+pending")

            harness.coordinator.events.test {
                assertIs<DrainPassOutcome.Remaining>(
                    harness.scheduler.fireActivation(STORE_NAME),
                )

                val scheduleFailed = assertIs<DrainScheduleFailed>(awaitItem())
                assertEventIdentity(scheduleFailed)
                assertEquals("safety failed", scheduleFailed.message)

                val started = assertIs<DrainActivationStarted>(awaitItem())
                assertEventIdentity(started)

                val scheduled = assertIs<DrainActivationScheduled>(awaitItem())
                assertEventIdentity(scheduled)
                assertEquals(30_000L, scheduled.delayMillis)

                val completed = assertIs<DrainPassCompleted>(awaitItem())
                assertEventIdentity(completed)
                assertEquals(1, completed.pendingIntents)
                assertEquals(0, completed.deadLetters)

                harness.coordinator.unregister(STORE_NAME)
                val cancelled = assertIs<DrainActivationCancelled>(awaitItem())
                assertEventIdentity(cancelled)

                assertIs<DrainPassOutcome.Unavailable>(
                    harness.coordinator.runActivation(STORE_NAME),
                )
                val passFailed = assertIs<DrainPassFailed>(awaitItem())
                assertEventIdentity(passFailed)
                assertEquals("name not registered", passFailed.message)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            harness.close()
        }
    }

    private fun assertEventIdentity(event: DrainSchedulerEvent) {
        assertEquals(STORE_NAME, event.storeName)
        assertEquals(42_424L, event.occurredAtEpochMillis)
    }
}

private const val STORE_NAME: String = "users"

private class RegisteredHarness(
    sourceOfTruth: SourceOfTruth<DrainTestKey, String>? = null,
    policy: DrainPolicy = DrainPolicy(),
) {
    val fixture = DrainFixture()
    val store = fixture.openStore(sourceOfTruth)
    val scheduler = RecordingDrainScheduler()
    val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)

    init {
        coordinator.register(STORE_NAME, store, policy)
    }

    fun close() {
        coordinator.close()
        store.close()
    }
}

private class ParkedFixture {
    val storage = InMemoryMutationJournalStorage()
    val backend = DrainFixtureBackend()
    var nowMillis: Long = 0L
    val clock =
        object : org.mobilenativefoundation.store6.core.seam.WallClock {
            override fun nowEpochMillis(): Long = nowMillis
        }
    lateinit var hostileRef: MutatorRef<DrainTestKey, String, String>
        private set

    private val registry =
        mutatorRegistry<DrainTestKey, String> {
            hostileRef =
                mutator(
                    id = "drain-hostile",
                    version = 1,
                    codec = DrainFixtureStringArgsCodec,
                    stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                ) { _, _ ->
                    error("projection failed")
                }
        }

    val store =
        mutationStore(
            registry = registry,
            server = backend,
            keyResolver = DrainTestKeyResolver,
            valueCodecVersion = 1,
            valueCodec = DrainFixtureStringArgsCodec,
        ) {
            fetcher { backend.load(it) }
            journalStorage(storage)
            wallClock(clock)
        }
}

private fun nonProgressingPostAckSourceOfTruth(): SourceOfTruth<DrainTestKey, String> {
    val delegate = throwingOnceSourceOfTruth()
    return object : SourceOfTruth<DrainTestKey, String> {
        override fun reader(key: DrainTestKey): Flow<String?> = delegate.reader(key)

        override suspend fun write(
            key: DrainTestKey,
            value: String,
        ) {
            delegate.write(key, value)
            error("fixture source of truth remains unavailable")
        }

        override suspend fun delete(key: DrainTestKey) = delegate.delete(key)

        override suspend fun deleteNamespace(namespace: StoreNamespace) =
            delegate.deleteNamespace(namespace)

        override suspend fun deleteAll() = delegate.deleteAll()
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
