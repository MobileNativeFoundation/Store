@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationAckPathTest {
    @Test
    fun ack_landsSotOrigin_withZeroAdditionalFetches() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("lands-sot")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            users.stream(key).test {
                awaitValue("base")
                users.mutate(key, mutation.ref, "optimistic")
                assertEquals(Origin.OVERLAY, awaitValue("optimistic").origin)
                val fetchesBeforeAck = backend.fetchCount

                users.drainOnce(key)

                val landed = awaitConfirmedValue("optimistic")
                assertEquals(Origin.SOT, landed.origin)
                assertEquals(fetchesBeforeAck, backend.fetchCount)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun ack_confirmFreshClearsDurableStaleness() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val bookkeeper = FakeBookkeeper()
        val key = MutationsTestKey("clears-stale")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
                bookkeeper(bookkeeper)
            }

        try {
            assertEquals("base", users.get(key))
            val fetchesBeforeAck = backend.fetchCount
            users.invalidate(key)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            users.mutate(key, mutation.ref, "confirmed")

            users.drainOnce(key)

            assertFalse(assertNotNull(bookkeeper.status(key)).durablyStale)
            users.stream(key).test {
                val confirmed = awaitConfirmedValue("confirmed")
                assertFalse(confirmed.isStale)
                assertEquals(fetchesBeforeAck, backend.fetchCount)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun ack_retiresIntent_andReprojectionShowsConfirmedBase() = runTest {
        val mutation = RenameMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    MutationAck(echo = "confirmed:$value", etag = "server-etag")
                }
            }
        val key = MutationsTestKey("retire")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            users.mutate(key, mutation.ref, "pending")

            users.drainOnce(key)

            assertEquals(emptyList(), users.pending(key))
            users.stream(key, Freshness.LocalOnly).test {
                val confirmed = awaitConfirmedValue("confirmed:pending")
                assertTrue(confirmed.origin == Origin.SOT || confirmed.origin == Origin.MEMORY)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun ack_neverReemitsOldBase() = runTest {
        val mutation = RenameMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    MutationAck(echo = "confirmed:$value", etag = "server-etag")
                }
            }
        val key = MutationsTestKey("never-old")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            users.stream(key).test {
                awaitValue("base")
                users.mutate(key, mutation.ref, "pending")
                awaitValue("pending")

                users.drainOnce(key)

                while (true) {
                    val data = awaitData()
                    assertNotEquals("base", data.value)
                    if (data.value == "confirmed:pending" && data.origin != Origin.OVERLAY) break
                }
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun retireHappensAfterAdoption() = runTest {
        val events = mutableListOf<String>()
        val journal = RetireOrderingJournal(events)
        val mutation = RenameMutation()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = echoingMutationServer(),
                journal = journal,
            )
        val key = MutationsTestKey("ordering")
        engine.bind(RecordingWriteHandle(events))
        engine.mutate(key, mutation.ref, "pending")

        engine.drainOnce(key, confirmedBase = "base")

        assertEquals(listOf("apply", "confirmFresh", "retire"), events)
    }

    @Test
    fun ackFailure_leavesIntentPending_andKeepsProjection() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("push-failure")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            val mutationId = users.mutate(key, mutation.ref, "optimistic")
            backend.offline = true

            users.drainOnce(key)

            assertEquals(listOf(mutationId), users.pending(key).map(PendingIntent::mutationId))
            users.stream(key, Freshness.LocalOnly).test {
                val optimistic = awaitValue("optimistic")
                assertEquals(Origin.OVERLAY, optimistic.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun hostileHead_stopsDrainWithoutPushOrRetirement() = runTest {
        lateinit var hostile: MutatorRef<MutationsTestKey, String, Unit>
        lateinit var healthy: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                hostile = mutator("hostile") { _, _ -> error("projection failed") }
                healthy = mutator("healthy") { _, value -> value }
            }
        val backend = FakeBackend()
        val engine = MutationEngine(registry, backend)
        val key = MutationsTestKey("hostile-head")
        engine.bind(RecordingWriteHandle(mutableListOf()))
        val hostileId = engine.mutate(key, hostile, Unit)
        val healthyId = engine.mutate(key, healthy, "tail")

        engine.drainOnce(key, confirmedBase = "base")

        assertEquals(emptyList(), backend.pushedValues)
        assertEquals(
            listOf(hostileId, healthyId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
    }

    @Test
    fun unknownHead_stopsDrainWithoutPushOrRetirement() = runTest {
        lateinit var healthy: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                healthy = mutator("healthy") { _, value -> value }
            }
        val backend = FakeBackend()
        val journal = InMemoryMutationJournal<String>()
        val engine = MutationEngine(registry, backend, journal)
        val key = MutationsTestKey("unknown-head")
        val unknownId = "unknown-mutation"
        val healthyId = "healthy-mutation"
        journal.append(
            key.identity(),
            JournalEntry(
                mutationId = unknownId,
                mutatorId = "removed-mutator",
                args = Unit,
            ),
        )
        journal.append(
            key.identity(),
            JournalEntry(
                mutationId = healthyId,
                mutatorId = healthy.id,
                args = "tail",
            ),
        )
        engine.bind(RecordingWriteHandle(mutableListOf()))

        engine.drainOnce(key, confirmedBase = "base")

        assertEquals(emptyList(), backend.pushedValues)
        assertEquals(
            listOf(unknownId, healthyId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
    }

    @Test
    fun adoptionFailurePropagates() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val engine = MutationEngine(mutation.registry, backend)
        val key = MutationsTestKey("adoption-failure")
        val store = store<MutationsTestKey, String> { fetcher { backend.load(it) } }
        val confirmedBase = store.get(key)
        engine.bind(assertNotNull(store.runtime()).writeHandle)
        val mutationId = engine.mutate(key, mutation.ref, "pending")
        store.close()

        val failure =
            assertFailsWith<IllegalStateException> {
                engine.drainOnce(key, confirmedBase)
            }

        assertEquals("Store is closed.", failure.message)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
    }

    @Test
    fun rawWriteHandleUnreachableThroughFacade() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }
        val bare = store<MutationsTestKey, String> { fetcher { "base" } }

        try {
            assertNull(users.runtime())
            assertNotNull(bare.runtime())
        } finally {
            users.close()
            bare.close()
        }
    }

    @Test
    fun mutateAfterCloseThrows() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("closed")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }
        users.close()

        val mutateFailure =
            assertFailsWith<IllegalStateException> {
                users.mutate(key, mutation.ref, "never-appended")
            }
        val drainFailure =
            assertFailsWith<IllegalStateException> {
                users.drainOnce(key)
            }
        val pendingFailure =
            assertFailsWith<IllegalStateException> {
                users.pending(key)
            }

        assertEquals("Store is closed.", mutateFailure.message)
        assertEquals("Store is closed.", drainFailure.message)
        assertEquals("Store is closed.", pendingFailure.message)
    }

    @Test
    fun drainOnce_progressivePrefixUsesServerEcho() = runTest {
        val mutation = AppendMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    when (value) {
                        "base+A" -> MutationAck(echo = "echo-A", etag = "etag-a")
                        "echo-A+B" -> MutationAck(echo = "echo-A+B", etag = "etag-b")
                        else -> error("unexpected prefix $value")
                    }
                }
            }
        val key = MutationsTestKey("progressive-prefix")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            users.mutate(key, mutation.ref, "+A")
            users.mutate(key, mutation.ref, "+B")

            users.drainOnce(key)

            assertEquals(listOf("base+A", "echo-A+B"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(key))
            assertEquals("echo-A+B", users.get(key, Freshness.LocalOnly))
        } finally {
            users.close()
        }
    }

    @Test
    fun drainOnce_nullPrefixStopsAndLeavesDeleteAndCreatePending() = runTest {
        lateinit var update: MutatorRef<MutationsTestKey, String, String>
        lateinit var delete: MutatorRef<MutationsTestKey, String, Unit>
        lateinit var create: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                update = mutator("update") { _, value -> value }
                delete = mutator<Unit>("delete") { _, _ -> null }
                create = mutator("create") { _, value -> value }
            }
        val backend = FakeBackend()
        val key = MutationsTestKey("null-prefix")
        val users =
            mutationStore(registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            users.mutate(key, update, "updated")
            val deleteId = users.mutate(key, delete, Unit)
            val createId = users.mutate(key, create, "recreated")

            users.drainOnce(key)

            assertEquals(listOf("updated"), backend.pushedValues)
            assertEquals(
                listOf(deleteId, createId),
                users.pending(key).map(PendingIntent::mutationId),
            )
            assertEquals("updated", users.get(key, Freshness.LocalOnly))
        } finally {
            users.close()
        }
    }

    @Test
    fun concurrentDrainOnce_serializesReadAndAdoption() = runTest {
        val mutation = RenameMutation()
        val pushStarted = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    pushStarted.complete(Unit)
                    releasePush.await()
                    MutationAck(echo = value, etag = "etag")
                }
            }
        val key = MutationsTestKey("concurrent-drain")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            users.mutate(key, mutation.ref, "pending")
            val first =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    users.drainOnce(key)
                }
            pushStarted.await()
            val second =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    users.drainOnce(key)
                }
            assertFalse(second.isCompleted)

            releasePush.complete(Unit)
            first.await()
            second.await()

            assertEquals(listOf("pending"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(key))
        } finally {
            releasePush.complete(Unit)
            users.close()
        }
    }

    @Test
    fun pushCancellation_propagatesAndLeavesIntentPending() = runTest {
        val mutation = RenameMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, _ -> throw CancellationException("push cancelled") }
            }
        val key = MutationsTestKey("push-cancellation")
        val users =
            mutationStore(mutation.registry, backend) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            val mutationId = users.mutate(key, mutation.ref, "pending")

            val failure =
                assertFailsWith<CancellationException> {
                    users.drainOnce(key)
                }

            assertEquals("push cancelled", failure.message)
            assertEquals(
                listOf(mutationId),
                users.pending(key).map(PendingIntent::mutationId),
            )
        } finally {
            users.close()
        }
    }
}

private class RenameMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref = mutator("rename") { _, value -> value }
        }
}

private class AppendMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref = mutator("append") { base, suffix -> base.orEmpty() + suffix }
        }
}

private class RetireOrderingJournal(
    private val events: MutableList<String>,
) : MutationJournal<String> {
    private val delegate = InMemoryMutationJournal<String>()

    override suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<String>,
    ): String = delegate.append(key, entry)

    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        assertEquals(listOf("apply", "confirmFresh"), events)
        delegate.retire(key, mutationId)
        events += "retire"
    }

    override fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<String>> =
        delegate.pendingSnapshot(key)
}

private class RecordingWriteHandle(
    private val events: MutableList<String>,
) : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        events += "apply"
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) {
        events += "confirmFresh"
    }
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitValue(
    expected: String,
): StoreResult.Data<String> {
    while (true) {
        val data = awaitData()
        if (data.value == expected) return data
    }
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitConfirmedValue(
    expected: String,
): StoreResult.Data<String> {
    while (true) {
        val data = awaitData()
        if (data.value == expected && data.origin != Origin.OVERLAY) return data
    }
}

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
