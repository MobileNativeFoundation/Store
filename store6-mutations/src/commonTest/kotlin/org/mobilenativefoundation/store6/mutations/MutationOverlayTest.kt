@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MutationOverlayTest {
    @Test
    fun noPendingIntents_isIdentity_andPassesThroughEnvelope() = runTest {
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {},
                echoingMutationServer(),
            )
        val key = MutationsTestKey("identity")
        val base = charArrayOf('b', 'a', 's', 'e').concatToString()

        assertSame(base, engine.overlay.apply(key, base))
    }

    @Test
    fun pendingIntent_projectsOverlayOrigin() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {
                    append = mutator("append") { base, suffix -> base.orEmpty() + suffix }
                },
                echoingMutationServer(),
            )
        val key = MutationsTestKey("active")
        val store = store<MutationsTestKey, String> {
            fetcher { "base" }
            overlay(engine.overlay)
        }

        try {
            store.stream(key).test {
                assertEquals("base", awaitData().value)

                engine.mutate(key, append, "+pending")
                var projected = awaitData()
                while (projected.value != "base+pending") {
                    projected = awaitData()
                }

                assertEquals(Origin.OVERLAY, projected.origin)
                assertEquals(Duration.ZERO, projected.age)
                assertFalse(projected.isStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun pendingCreate_overAbsentBase_projectsOptimisticCreate() = runTest {
        lateinit var create: MutatorRef<MutationsTestKey, String, String>
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {
                    create = mutator("create") { _, value -> value }
                },
                echoingMutationServer(),
            )
        val key = MutationsTestKey("create")
        engine.mutate(key, create, "optimistic")

        assertEquals("optimistic", engine.overlay.apply(key, null))
    }

    @Test
    fun pendingDelete_overResidentBase_projectsAbsence() = runTest {
        lateinit var delete: MutatorRef<MutationsTestKey, String, Unit>
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {
                    delete = mutator<Unit>("delete") { _, _ -> null }
                },
                echoingMutationServer(),
            )
        val key = MutationsTestKey("delete")
        engine.mutate(key, delete, Unit)

        assertNull(engine.overlay.apply(key, "resident"))
    }

    @Test
    fun changes_toleratesConcurrentCollectors() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {
                    append = mutator("append") { base, suffix -> base.orEmpty() + suffix }
                },
                echoingMutationServer(),
            )
        val key = MutationsTestKey("fan-out")
        val observed = List(CONCURRENT_COLLECTORS) { CompletableDeferred<StoreKey>() }
        val collectors =
            observed.map { signal ->
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    signal.complete(engine.overlay.changes.first())
                }
            }

        engine.mutate(key, append, "+pending")

        observed.forEach { signal ->
            assertSame(key, signal.await())
        }
        collectors.forEach { it.join() }
    }

    @Test
    fun cancelledMutate_afterAppendStillPublishesKeyChange() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val engine =
            MutationEngine(
                mutatorRegistry<MutationsTestKey, String> {
                    rename = mutator("rename") { _, value -> value }
                },
                echoingMutationServer(),
            )
        val firstKey = MutationsTestKey("append-signal-blocker")
        val cancelledKey = MutationsTestKey("append-signal-cancelled")
        val firstObserved = CompletableDeferred<StoreKey>()
        val cancelledObserved = CompletableDeferred<StoreKey>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var observed = 0
                engine.changes.collect { key ->
                    when (observed++) {
                        0 -> firstObserved.complete(key)
                        1 -> cancelledObserved.complete(key)
                    }
                }
            }

        try {
            engine.mutate(firstKey, rename, "first")
            val cancelledMutation =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.mutate(cancelledKey, rename, "second")
                }
            assertFalse(cancelledMutation.isCompleted)
            assertEquals(1, engine.pending(cancelledKey).size)

            cancelledMutation.cancel()
            testScheduler.runCurrent()
            cancelledMutation.join()

            assertSame(firstKey, firstObserved.await())
            assertTrue(cancelledMutation.isCancelled)
            assertTrue(
                cancelledObserved.isCompleted,
                "a committed append must retain its key-change handoff across cancellation",
            )
            assertSame(cancelledKey, cancelledObserved.await())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun cancelledDrain_afterRetireStillPublishesKeyChange() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                rename = mutator("rename") { _, value -> value }
            }
        val journal = InMemoryMutationJournal<String>()
        val engine = MutationEngine(registry, echoingMutationServer(), journal)
        engine.bind(NoopWriteHandle)
        val firstKey = MutationsTestKey("retire-signal-blocker")
        val retiredKey = MutationsTestKey("retire-signal-cancelled")
        val firstObserved = CompletableDeferred<StoreKey>()
        val retiredObserved = CompletableDeferred<StoreKey>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var observed = 0
                engine.changes.collect { key ->
                    when (observed++) {
                        0 -> firstObserved.complete(key)
                        1 -> retiredObserved.complete(key)
                    }
                }
            }

        try {
            engine.mutate(firstKey, rename, "first")
            journal.append(
                retiredKey.identity(),
                JournalEntry(
                    mutationId = "retired-mutation",
                    mutatorId = rename.id,
                    args = "second",
                ),
            )
            val cancelledDrain =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.drainOnce(retiredKey, confirmedBase = "base")
                }
            assertFalse(cancelledDrain.isCompleted)
            assertEquals(emptyList(), engine.pending(retiredKey))

            cancelledDrain.cancel()
            testScheduler.runCurrent()
            cancelledDrain.join()

            assertSame(firstKey, firstObserved.await())
            assertTrue(cancelledDrain.isCancelled)
            assertTrue(
                retiredObserved.isCompleted,
                "a completed retirement must retain its key-change handoff across cancellation",
            )
            assertSame(retiredKey, retiredObserved.await())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun changes_neverCompletes_andNeverFails() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val journal = InMemoryMutationJournal<String>()
        val engine =
            MutationEngine(
                registry =
                    mutatorRegistry<MutationsTestKey, String> {
                        append = mutator("append") { base, suffix -> base.orEmpty() + suffix }
                    },
                server = echoingMutationServer(),
                journal = journal,
            )
        val key = MutationsTestKey("lifecycle")
        val store = store<MutationsTestKey, String> {
            fetcher { "base" }
            overlay(engine.overlay)
        }
        val baseObserved = CompletableDeferred<Unit>()
        val streamCollector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                store.stream(key).collect { result ->
                    if (result is StoreResult.Data && result.value == "base") {
                        baseObserved.complete(Unit)
                    }
                }
            }

        try {
            baseObserved.await()
            engine.overlay.changes.test {
                val firstMutation = engine.mutate(key, append, "+first")
                assertSame(key, awaitItem())

                journal.retire(key.identity(), firstMutation)
                store.close()
                streamCollector.cancelAndJoin()

                engine.mutate(key, append, "+second")
                assertSame(key, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
            streamCollector.cancelAndJoin()
        }
    }
}

private object NoopWriteHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private const val CONCURRENT_COLLECTORS = 8
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
