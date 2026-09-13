@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class CoordinatorLifecycleTest {
    @Test
    fun attachTwiceThrows() = runTest {
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler)
        try {
            assertFailsWith<IllegalStateException> {
                mutationDrainCoordinator(scheduler)
            }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun schedulesBeforeAttachThrow() = runTest {
        val scheduler = RecordingDrainScheduler()
        val request =
            DrainRequest(
                storeName = "users",
                constraints = DrainConstraints(),
                earliestDelay = 30.seconds,
            )

        assertFailsWith<IllegalStateException> {
            scheduler.schedule(request)
        }
        assertFailsWith<IllegalStateException> {
            scheduler.cancel("users")
        }
    }

    @Test
    fun reconcileAfterCloseThrows() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        val coordinator = mutationDrainCoordinator(RecordingDrainScheduler(), fixture.clock)
        coordinator.close()
        try {
            assertFailsWith<IllegalStateException> {
                coordinator.reconcile()
            }
            assertFailsWith<IllegalStateException> {
                coordinator.register("users", store)
            }
            assertFailsWith<IllegalStateException> {
                coordinator.unregister("users")
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun registerValidatesConstraintsThroughScheduler() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        val policy =
            DrainPolicy(
                constraints =
                    DrainConstraints(
                        requiresNetwork = false,
                        requiresCharging = true,
                    ),
            )
        scheduler.validateThrows = IllegalArgumentException("unsupported constraints")
        try {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    coordinator.register("users", store, policy)
                }

            assertEquals("unsupported constraints", failure.message)
            assertEquals(1, scheduler.validated.size)
            assertSame(policy.constraints, scheduler.validated.single())
        } finally {
            coordinator.close()
            store.close()
        }
    }

    @Test
    fun multiStoreIsolation() = runTest {
        val fixtureA = DrainFixture()
        val fixtureB = DrainFixture()
        fixtureA.backend.offline = true
        val storeA = fixtureA.openStore()
        val storeB = fixtureB.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixtureA.clock)
        coordinator.register("a", storeA)
        coordinator.register("b", storeB)
        try {
            storeA.mutate(DrainTestKey("a-key"), fixtureA.appendRef, "+a")
            storeB.mutate(DrainTestKey("b-key"), fixtureB.appendRef, "+b")

            val firstA =
                assertIs<DrainPassOutcome.Remaining>(
                    scheduler.fireActivation("a"),
                )

            assertEquals(30.seconds, firstA.scheduledDelay)
            assertEquals(1, storeB.pendingWrites().size)
            assertTrue(scheduler.scheduled.none { request -> request.storeName == "b" })
            assertTrue(scheduler.cancelled.none { name -> name == "b" })

            fixtureA.backend.offline = false
            fixtureA.nowMillis = 30.seconds.inWholeMilliseconds
            assertIs<DrainPassOutcome.Cleared>(scheduler.fireActivation("a"))

            assertEquals(1, storeB.pendingWrites().size)
            assertTrue(scheduler.scheduled.none { request -> request.storeName == "b" })
            assertEquals(listOf("a"), scheduler.cancelled)

            assertIs<DrainPassOutcome.Cleared>(scheduler.fireActivation("b"))

            assertEquals(emptyList(), storeB.pendingWrites())
            assertEquals(listOf("+b"), fixtureB.backend.receivedPushes)
            assertEquals(listOf("a", "b"), scheduler.cancelled)
            assertEquals(
                listOf(1.hours, 30.seconds, 1.hours),
                scheduler.scheduled
                    .filter { request -> request.storeName == "a" }
                    .map(DrainRequest::earliestDelay),
            )
            assertEquals(
                listOf(1.hours),
                scheduler.scheduled
                    .filter { request -> request.storeName == "b" }
                    .map(DrainRequest::earliestDelay),
            )
        } finally {
            coordinator.close()
            storeA.close()
            storeB.close()
        }
    }

    @Test
    fun cancelDuringExecutingActivationIsLegal() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val gate = CompletableDeferred<Unit>()
        fixture.backend.pushGate = gate
        val store = fixture.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        coordinator.register("users", store)
        try {
            store.mutate(DrainTestKey("cancel-running"), fixture.appendRef, "+pending")
            val pass = async { scheduler.fireActivation("users") }
            fixture.backend.pushEntered.awaitFromDefaultContext()
            assertEquals(1, fixture.backend.maxConcurrentPushes)

            scheduler.cancel("users")
            gate.complete(Unit)
            val outcome = assertIs<DrainPassOutcome.Remaining>(pass.await())

            assertEquals(1, outcome.pendingIntents)
            assertEquals(30.seconds, outcome.scheduledDelay)
            assertEquals(
                listOf(
                    "schedule(users, 1h)",
                    "cancel(users)",
                    "schedule(users, 30s)",
                ),
                scheduler.log.filterNot { entry -> entry.startsWith("validate(") },
            )
        } finally {
            coordinator.close()
            store.close()
        }
    }

    @Test
    fun registerUnregisterConcurrentWithPasses() = runTest {
        val primaryFixture = DrainFixture()
        primaryFixture.backend.offline = true
        val gate = CompletableDeferred<Unit>()
        primaryFixture.backend.pushGate = gate
        val primaryStore = primaryFixture.openStore()
        val otherFixture = DrainFixture()
        val otherStore = otherFixture.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, primaryFixture.clock)
        coordinator.register("primary", primaryStore)
        try {
            primaryStore.mutate(
                DrainTestKey("primary-key"),
                primaryFixture.appendRef,
                "+pending",
            )
            val pass = async { scheduler.fireActivation("primary") }
            primaryFixture.backend.pushEntered.awaitFromDefaultContext()
            assertEquals(1, primaryFixture.backend.maxConcurrentPushes)

            val churn =
                async {
                    repeat(100) { index ->
                        val name = "other-$index"
                        coordinator.register(name, otherStore)
                        coordinator.unregister(name)
                    }
                }
            churn.await()

            assertFalse("primary" in scheduler.cancelled)
            gate.complete(Unit)
            val outcome = assertIs<DrainPassOutcome.Remaining>(pass.await())

            assertEquals(1, outcome.pendingIntents)
            assertEquals(30.seconds, outcome.scheduledDelay)
            assertEquals(2, scheduler.scheduled.count { request -> request.storeName == "primary" })
            assertEquals(1, primaryStore.pendingWrites().size)
        } finally {
            coordinator.close()
            primaryStore.close()
            otherStore.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
