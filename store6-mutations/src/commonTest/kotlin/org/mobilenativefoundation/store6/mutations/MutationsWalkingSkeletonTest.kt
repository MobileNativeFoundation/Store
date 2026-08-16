@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationsWalkingSkeletonTest {
    @Test
    fun offlineMutation_projectsOverlay_thenAckLandsConfirmedWithoutRefetch() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                rename =
                    mutator(
                        id = "rename",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, nextName -> MutationPresence.Present(nextName) }
            }
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    MutationPresentAck(
                        authoritative = "confirmed:$value",
                        etag = "server-etag",
                        canonicalKey = null,
                    )
                }
            }
        val key = MutationsTestKey("quickstart")
        val users =
            mutationStore(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }
        val hydrationWrittenAwaiter =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.keyEvents.first { event ->
                    event is KeyEvents.Written &&
                        event.key === key &&
                        event.origin == Origin.FETCHER
                } as KeyEvents.Written
            }

        try {
            users.stream(key).test {
                awaitValue("base")
                val hydrationWritten = hydrationWrittenAwaiter.await()
                assertSame(key, hydrationWritten.key)
                assertEquals(Origin.FETCHER, hydrationWritten.origin)
                val fetchesAfterHydration = backend.fetchCount
                backend.offline = true

                val mutationId = users.mutate(key, rename, "optimistic")
                assertTrue(mutationId.isNotBlank())
                assertEquals(emptyList(), backend.pushedValues)
                assertEquals(
                    listOf(mutationId),
                    users.pending(key).map(PendingIntent::mutationId),
                )

                val optimistic = awaitOverlayValue("optimistic")
                assertEquals(Origin.OVERLAY, optimistic.origin)

                // KeyEvents has replay = 0. UNDISPATCHED enters first() before async returns, so
                // this collector is subscribed before drain(key) can emit Written.
                val writtenAwaiter =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        users.keyEvents.first { event ->
                            event is KeyEvents.Written &&
                                event.key === key &&
                                event.origin == Origin.SOT
                        } as KeyEvents.Written
                    }
                try {
                    backend.offline = false
                    users.drain(key)
                    assertEquals(listOf("optimistic"), backend.pushedValues)

                    val confirmed = awaitConfirmedWithoutOldBase("confirmed:optimistic", "base")
                    assertTrue(
                        confirmed.origin == Origin.SOT || confirmed.origin == Origin.MEMORY,
                        "expected SOT or MEMORY after ack, was ${confirmed.origin}",
                    )

                    val written = writtenAwaiter.await()
                    assertSame(key, written.key)
                    assertEquals(Origin.SOT, written.origin)
                    assertEquals(fetchesAfterHydration, backend.fetchCount)
                    assertEquals(emptyList(), users.pending(key))
                    assertNull(users.runtime())
                } finally {
                    writtenAwaiter.cancelAndJoin()
                }

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            try {
                hydrationWrittenAwaiter.cancelAndJoin()
            } finally {
                users.close()
            }
        }
    }

    @Test
    fun hostileMutator_isContained_andHealthyMutationKeepsLiveStreamWorking() = runTest {
        val projectionFailure = IllegalStateException("hostile projection")
        lateinit var hostile: MutatorRef<MutationsTestKey, String, Unit>
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                hostile =
                    mutator(
                        id = "hostile",
                        version = 1,
                        codec = FixtureUnitArgsCodec,
                        stales = noStales(),
                    ) { _, _ -> throw projectionFailure }
                append =
                    mutator(
                        id = "append",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { base, suffix ->
                        MutationPresence.Present(
                            ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                        )
                    }
            }
        val backend = FakeBackend()
        val key = MutationsTestKey("hostile")
        val users =
            mutationStore(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))

            users.stream(key).test {
                awaitValue("base")

                users.poisoned.test {
                    val hostileId = users.mutate(key, hostile, Unit)
                    val poisoned = awaitPoisoned(hostileId)
                    assertEquals(hostile.id, poisoned.mutatorId)
                    assertSame(projectionFailure, poisoned.failure)

                    val healthyId = users.mutate(key, append, "+healthy")
                    assertTrue(healthyId.isNotBlank())
                    val healthy = awaitOverlayValue("base+healthy")
                    assertEquals(Origin.OVERLAY, healthy.origin)
                    cancelAndIgnoreRemainingEvents()
                }

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
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

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitOverlayValue(
    expected: String,
): StoreResult.Data<String> {
    while (true) {
        val data = awaitData()
        if (data.value == expected && data.origin == Origin.OVERLAY) return data
    }
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitConfirmedWithoutOldBase(
    expected: String,
    oldBase: String,
): StoreResult.Data<String> {
    while (true) {
        val data = awaitData()
        assertNotEquals(oldBase, data.value, "old base re-emitted after acknowledgement")
        if (
            data.value == expected &&
            (data.origin == Origin.SOT || data.origin == Origin.MEMORY)
        ) {
            return data
        }
    }
}

private suspend fun ReceiveTurbine<PoisonedIntent>.awaitPoisoned(
    expectedMutationId: String,
): PoisonedIntent {
    while (true) {
        val poisoned = awaitItem()
        if (poisoned.mutationId == expectedMutationId) return poisoned
    }
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
