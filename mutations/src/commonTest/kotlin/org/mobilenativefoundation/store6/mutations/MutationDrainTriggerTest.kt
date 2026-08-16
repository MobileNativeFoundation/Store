@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class MutationDrainTriggerTest {
    @Test
    fun launchTrigger_hostDrainAfterConstructionResumesPendingWork() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = TriggerMutations()
        val backend = retainingTriggerBackend()
        val clock = SchedulerTriggerClock { testScheduler.currentTime }
        val key = MutationsTestKey("launch-trigger")

        openTriggerStore(storage, mutations, backend, clock).use { first ->
            first.mutate(key, mutations.append, "+pending")
        }
        assertEquals(emptyList(), backend.receivedPushes)

        openTriggerStore(storage, mutations, backend, clock).use { launched ->
            testScheduler.runCurrent()
            assertEquals(emptyList(), backend.receivedPushes)
            assertEquals(StoredPhase.UNPREPARED, triggerExecutionPhase(storage))

            launched.drain()
            assertEquals(emptyList(), launched.pending(key))
        }

        assertEquals(listOf("launch-trigger"), backend.receivedPushes.map { it.key.canonicalId() })
        assertEquals(StoredPhase.RETIRED, triggerExecutionPhase(storage))
    }

    @Test
    fun connectivityRegainTrigger_hostDrainFlushesOfflineBacklog() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = TriggerMutations()
        val backend = retainingTriggerBackend().apply { offline = true }
        val clock = SchedulerTriggerClock { testScheduler.currentTime }
        val key = MutationsTestKey("connectivity-trigger")

        openTriggerStore(storage, mutations, backend, clock).use { users ->
            users.mutate(key, mutations.append, "+offline")
            users.drain()
            assertEquals(StoredPhase.READY, triggerExecutionPhase(storage))
            assertEquals(1, triggerAttempt(storage))
            assertEquals(listOf(key.canonicalId()), users.pendingWrites().map { it.canonicalId })

            backend.offline = false
            testScheduler.advanceTimeBy(1_001L)
            users.drain()

            assertEquals(emptyList(), users.pendingWrites())
            assertEquals(StoredPhase.RETIRED, triggerExecutionPhase(storage))
        }
        assertEquals(listOf("+offline"), backend.pushedValues)
    }

    @Test
    fun postMutationTrigger_keyedDrainPushesWithoutAutoScheduler() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = TriggerMutations()
        val backend = FakeBackend()
        val clock = SchedulerTriggerClock { testScheduler.currentTime }
        val key = MutationsTestKey("post-mutation-trigger")

        openTriggerStore(storage, mutations, backend, clock).use { users ->
            users.mutate(key, mutations.append, "+pending")
            assertEquals(emptyList(), backend.receivedPushes)

            users.drain(key)

            assertEquals(1, backend.receivedPushes.size)
            assertEquals(emptyList(), users.pending(key))
        }
    }

    @Test
    fun mutateNeverStartsATransportPush() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = TriggerMutations()
        val backend = FakeBackend()
        val clock = SchedulerTriggerClock { testScheduler.currentTime }
        val key = MutationsTestKey("mutate-no-push")

        openTriggerStore(storage, mutations, backend, clock).use { users ->
            val mutationId = users.mutate(key, mutations.append, "+pending")
            testScheduler.runCurrent()

            assertEquals(emptyList(), backend.receivedPushes)
            assertEquals(listOf(mutationId), users.pending(key).map(PendingIntent::mutationId))
            assertEquals(StoredPhase.UNPREPARED, triggerExecutionPhase(storage))
        }
    }

    @Test
    fun hydrationNeverStartsADrainPass() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = TriggerMutations()
        val backend = FakeBackend()
        val clock = SchedulerTriggerClock { testScheduler.currentTime }
        val key = MutationsTestKey("hydration-no-drain")

        openTriggerStore(storage, mutations, backend, clock).use { first ->
            first.mutate(key, mutations.append, "+pending")
        }
        assertEquals(StoredPhase.UNPREPARED, triggerExecutionPhase(storage))

        openTriggerStore(storage, mutations, backend, clock).use { reopened ->
            assertEquals("base", reopened.get(key))
            assertEquals(1, reopened.pending(key).size)
            testScheduler.runCurrent()

            assertEquals(emptyList(), backend.receivedPushes)
            assertEquals(StoredPhase.UNPREPARED, triggerExecutionPhase(storage))
            assertEquals(0, triggerAttempt(storage))
        }
    }
}

private const val TRIGGER_CLIENT_ID: String = "client-0"

private class TriggerMutations {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            append =
                mutator(
                    id = "trigger-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }
}

private class SchedulerTriggerClock(
    private val now: () -> Long,
) : WallClock {
    override fun nowEpochMillis(): Long = now()
}

private fun retainingTriggerBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun openTriggerStore(
    storage: InMemoryMutationJournalStorage,
    mutations: TriggerMutations,
    backend: FakeBackend,
    wallClock: WallClock,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = mutations.registry,
        server = backend,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
    ) {
        fetcher { backend.load(it) }
        journalStorage(storage)
        wallClock(wallClock)
    }

private inline fun <K : org.mobilenativefoundation.store6.core.StoreKey, V : Any, R>
    MutationStore<K, V>.use(block: (MutationStore<K, V>) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private suspend fun triggerExecutionPhase(storage: InMemoryMutationJournalStorage): StoredPhase =
    storage.transaction { transaction -> transaction.executions(TRIGGER_CLIENT_ID).single().phase }

private suspend fun triggerAttempt(storage: InMemoryMutationJournalStorage): Int =
    storage.transaction { transaction -> transaction.executions(TRIGGER_CLIENT_ID).single().attempt }

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
