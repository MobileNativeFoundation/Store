@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Truthful pending/pendingWrites/deadLetters snapshots and the normalized failure carrier's
 * sanitization contract. These tests exercise inspection shapes only and never fake the durable
 * scheduler: `REFRESHING` and `APPLYING_EFFECTS` have no producer on the in-memory path and are
 * proven through the frozen total mapping; the phases the in-memory pass truthfully produces are
 * observed live. Restart hydration is covered by `MutationJournalContractTest`; parking,
 * declined/parked scheduling, and dead-letter production by `MutationDrainParkingTest`.
 */
class MutationInspectionTest {
    @Test
    fun pendingStates_coverEveryActiveExecutionPhase() = runTest {
        // The total mapping: every nonterminal active phase maps to exactly
        // one public state; PARKED maps only to deadLetters() and RETIRED to neither API.
        assertEquals(
            listOf(
                MutationExecutionPhase.UNPREPARED to MutationPendingState.PENDING,
                MutationExecutionPhase.READY to MutationPendingState.PENDING,
                MutationExecutionPhase.INFLIGHT to MutationPendingState.INFLIGHT,
                MutationExecutionPhase.REFRESH_REQUIRED to MutationPendingState.REFRESHING,
                MutationExecutionPhase.ACKED to MutationPendingState.ADOPTING,
                MutationExecutionPhase.EFFECTS_PENDING to MutationPendingState.APPLYING_EFFECTS,
                MutationExecutionPhase.PARKED to null,
                MutationExecutionPhase.RETIRED to null,
            ),
            MutationExecutionPhase.entries.map { phase -> phase to phase.toPendingStateOrNull() },
        )

        // Live: the phases the in-memory foreground pass truthfully produces are visible through
        // pending(): PENDING at enqueue, INFLIGHT while the push is suspended, ADOPTING inside
        // the write-handle adoption window, and removal after retirement.
        val mutation = InspectionRenameMutation()
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    pushEntered.complete(Unit)
                    releasePush.await()
                    MutationPresentAck(authoritative = value, etag = "etag", canonicalKey = null)
                }
            }
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
            )
        val observedDuringAdoption = mutableListOf<MutationPendingState>()
        val probeHandle =
            object : StoreWriteHandle<MutationsTestKey, String> {
                override suspend fun apply(
                    key: MutationsTestKey,
                    value: String,
                ) {
                    observedDuringAdoption += engine.pending(key).single().state
                }

                override suspend fun markStale(key: MutationsTestKey) = Unit

                override suspend fun confirmFresh(
                    key: MutationsTestKey,
                    etag: String?,
                ) = Unit
            }
        engine.bind(probeHandle)
        val key = MutationsTestKey("phase-walk")
        engine.mutate(key, mutation.ref, "pending-value")

        assertEquals(MutationPendingState.PENDING, engine.pending(key).single().state)

        val drainPass =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.drain(key)
            }
        pushEntered.await()
        assertEquals(MutationPendingState.INFLIGHT, engine.pending(key).single().state)

        releasePush.complete(Unit)
        drainPass.await()

        assertEquals(listOf(MutationPendingState.ADOPTING), observedDuringAdoption)
        // Retired appears in neither inspection API.
        assertEquals(emptyList(), engine.pending(key))
        assertEquals(emptyList(), engine.pendingWrites())
        assertEquals(emptyList(), engine.deadLetters())
    }

    @Test
    fun pendingWrites_returnsMultiKeyIdentitySnapshots() = runTest {
        val mutation = InspectionRenameMutation()
        val backend = FakeBackend()
        val clock = TestWallClock(startEpochMillis = 1_000L)
        val firstKey = MutationsTestKey("snapshot-a")
        val secondKey = MutationsTestKey("snapshot-b")
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                wallClock(clock)
            }

        try {
            val firstId = users.mutate(firstKey, mutation.ref, "one")
            clock.setEpochMillis(2_000L)
            val secondId = users.mutate(secondKey, mutation.ref, "two")
            clock.setEpochMillis(3_000L)
            val thirdId = users.mutate(firstKey, mutation.ref, "three")

            val rows = users.pendingWrites()

            // All identities, durable client-sequence order, real enqueue stamps.
            assertEquals(listOf(firstId, secondId, thirdId), rows.map(PendingIntent::mutationId))
            assertEquals(
                listOf("snapshot-a", "snapshot-b", "snapshot-a"),
                rows.map(PendingIntent::canonicalId),
            )
            assertTrue(rows.all { row -> row.namespace == "mutations" })
            assertEquals(listOf(1_000L, 2_000L, 3_000L), rows.map(PendingIntent::createdAtEpochMillis))
            assertTrue(rows.all { row -> row.state == MutationPendingState.PENDING })
            assertTrue(rows.all { row -> row.attempt == 0 })
        } finally {
            users.close()
        }
    }

    @Test
    fun pendingAndDeadLetters_excludeRetiredHistory() = runTest {
        val mutation = InspectionRenameMutation()
        val backend = FakeBackend()
        val retiredKey = MutationsTestKey("history-retired")
        val activeKey = MutationsTestKey("history-active")
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            val retiredId = users.mutate(retiredKey, mutation.ref, "retired-value")
            users.drain(retiredKey)
            val activeId = users.mutate(activeKey, mutation.ref, "active-value")

            assertEquals(emptyList(), users.pending(retiredKey))
            assertEquals(listOf(activeId), users.pendingWrites().map(PendingIntent::mutationId))
            assertEquals(emptyList(), users.deadLetters())
            assertFalse(users.pendingWrites().any { row -> row.mutationId == retiredId })
        } finally {
            users.close()
        }
    }

    @Test
    fun deadLettersContainOnlyParkedExecutions() = runTest {
        val mutation = InspectionRenameMutation()
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationKeyResolver { throw IllegalStateException("unresolvable") },
                baseReader = { "base" },
            )
        engine.bind(InspectionNoopHandle)
        val key = MutationsTestKey("never-parked-at-021")
        val mutationId = engine.mutate(key, mutation.ref, "still-pending")
        engine.clearLiveKeyCache()

        engine.drain()

        // Dead letters contain only durably PARKED executions. A codec-less engine records the
        // normalized in-memory carrier but never parks, so the failed identity stays pending and
        // the dead-letter list stays empty.
        assertEquals(emptyList(), engine.deadLetters())
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        assertEquals(1, engine.drainFailuresForInspection().size)
    }

    @Test
    fun failureSanitizesControlsAndTruncatesUtf8AtCodePointBoundaries() = runTest {
        // Stack-trace lines and ISO controls never survive; the first line alone is kept.
        val controlled =
            sanitizedMutationFailure(
                kind = MutationFailureKind.IDENTITY,
                detail = "a\tbc\nat frame.one(File.kt:1)",
                message = "first line\r\nsecond line",
                occurredAtEpochMillis = 5L,
            )
        assertEquals("abc", controlled.detail)
        assertEquals("first line", controlled.message)

        // Two-byte code points: 64 alphas are exactly the 128-byte detail budget; one more
        // character is dropped whole.
        val twoByte =
            sanitizedMutationFailure(
                kind = MutationFailureKind.IDENTITY,
                detail = "α".repeat(64) + "x",
                message = "",
                occurredAtEpochMillis = 5L,
            )
        assertEquals("α".repeat(64), twoByte.detail)
        assertEquals(128, twoByte.detail.encodeToByteArray().size)

        // Three-byte code points: 43 euro signs are 129 bytes; truncation keeps 42 whole
        // characters (126 bytes), never a partial sequence.
        val threeByte =
            sanitizedMutationFailure(
                kind = MutationFailureKind.IDENTITY,
                detail = "€".repeat(43),
                message = "",
                occurredAtEpochMillis = 5L,
            )
        assertEquals("€".repeat(42), threeByte.detail)
        assertEquals(126, threeByte.detail.encodeToByteArray().size)

        // Four-byte code points: a surrogate pair straddling the boundary is dropped whole, so
        // no lone surrogate can survive truncation.
        val musicalG = "𝄞"
        val surrogate =
            sanitizedMutationFailure(
                kind = MutationFailureKind.IDENTITY,
                detail = "a".repeat(126) + musicalG,
                message = "",
                occurredAtEpochMillis = 5L,
            )
        assertEquals("a".repeat(126), surrogate.detail)
        assertFalse(surrogate.detail.last().isHighSurrogate())

        // The message budget is 1,024 bytes with the same code-point rule.
        val wideMessage =
            sanitizedMutationFailure(
                kind = MutationFailureKind.IDENTITY,
                detail = "",
                message = "б".repeat(513),
                occurredAtEpochMillis = 5L,
            )
        assertEquals("б".repeat(512), wideMessage.message)
        assertEquals(1_024, wideMessage.message.encodeToByteArray().size)
    }

    @Test
    fun failureCarrierContainsNoThrowableOrStoreError() = runTest {
        // Compile-level: the carrier exposes only an append-only kind, two sanitized strings,
        // and an epoch stamp. No property can hold a Throwable or StoreError.
        val kind: KProperty1<MutationFailure, MutationFailureKind> = MutationFailure::kind
        val detail: KProperty1<MutationFailure, String> = MutationFailure::detail
        val message: KProperty1<MutationFailure, String> = MutationFailure::message
        val occurredAt: KProperty1<MutationFailure, Long> = MutationFailure::occurredAtEpochMillis
        assertEquals(
            listOf("kind", "detail", "message", "occurredAtEpochMillis"),
            listOf(kind.name, detail.name, message.name, occurredAt.name),
        )

        // Engine path: a resolver throw with a cause chain normalizes to sanitized strings and a
        // clock stamp; nothing of the original Throwable — class, frames, or cause — survives.
        val mutation = InspectionRenameMutation()
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver =
                    MutationKeyResolver {
                        throw IllegalStateException(
                            "lookup failed",
                            IllegalArgumentException("root cause"),
                        )
                    },
                baseReader = { "base" },
                wallClock = TestWallClock(startEpochMillis = 9_000L),
            )
        engine.bind(InspectionNoopHandle)
        val key = MutationsTestKey("carrier-shape")
        engine.mutate(key, mutation.ref, "value")
        engine.clearLiveKeyCache()

        engine.drain()

        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        // The exact thrown first line and nothing else: no exception class name, no cause text.
        assertEquals("lookup failed", failure.message)
        assertFalse(failure.message.contains("IllegalStateException"))
        assertFalse(failure.message.contains("root cause"))
        assertEquals(9_000L, failure.occurredAtEpochMillis)
    }

    @Test
    fun poisonRemainsExactThrowableBesideNormalizedCarrier() = runTest {
        val projectionFailure = IllegalStateException("hostile projection")
        lateinit var hostile: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                hostile =
                    mutator(
                        id = "hostile",
                        version = 1,
                        codec = inertArgsCodec<Unit>(),
                        stales = noStales(),
                    ) { _, _ -> throw projectionFailure }
            }
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
            )
        engine.bind(InspectionNoopHandle)
        val key = MutationsTestKey("poison-beside-carrier")
        val mutationId = engine.mutate(key, hostile, Unit)

        engine.drain(key)

        // Poison is the ephemeral exact-Throwable flow; it is not a drain failure carrier,
        // and the projection throw records none.
        val poisoned = engine.poisoned.replayCache.single()
        assertEquals(mutationId, poisoned.mutationId)
        assertSame(projectionFailure, poisoned.failure)
        assertEquals(emptyList(), engine.drainFailuresForInspection())
        assertEquals(emptyList(), backend.pushedValues)
    }

    @Test
    fun failedPush_recordsCompletedAttemptFact() = runTest {
        val mutation = InspectionRenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("attempt-facts")
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            users.mutate(key, mutation.ref, "pending")
            backend.offline = true

            users.drain(key)
            assertEquals(1, users.pending(key).single().attempt)
            assertEquals(MutationPendingState.PENDING, users.pending(key).single().state)

            users.drain(key)
            assertEquals(2, users.pending(key).single().attempt)
        } finally {
            users.close()
        }
    }
}

private class InspectionRenameMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref =
                mutator(
                    id = "rename",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, value -> MutationPresence.Present(value) }
        }
}

private object InspectionNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
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

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
