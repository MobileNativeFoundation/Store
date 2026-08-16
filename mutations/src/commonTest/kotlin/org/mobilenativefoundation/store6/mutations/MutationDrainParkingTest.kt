@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The complete pre-ack parking inventory. */
class MutationDrainParkingTest {
    @Test
    fun unresolvedPreAckIdentityParksWithoutBlockingOtherIdentities() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val unresolved = MutationsTestKey("unresolved")
        val healthy = MutationsTestKey("healthy")
        val resolver =
            MutationKeyResolver<MutationsTestKey> { identity ->
                if (identity.canonicalId == unresolved.canonicalId()) {
                    throw IllegalStateException("identity lookup failed")
                }
                MutationsTestKeyResolver.resolve(identity)
            }
        val engine = openParkingEngine(storage, mutations, backend, resolver)
        val unresolvedId = engine.mutate(unresolved, mutations.append, "+lost")
        val healthyId = engine.mutate(healthy, mutations.append, "+ok")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(listOf(healthy.canonicalId()), backend.receivedPushes.map { it.identity.canonicalId })
        assertEquals(listOf("base+ok"), backend.pushedValues)
        assertEquals(emptyList(), engine.pendingWrites())
        val dead = engine.deadLetters().single()
        assertEquals(unresolvedId, dead.mutationId)
        assertEquals(MutationFailureKind.IDENTITY, dead.failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_THROW, dead.failure.detail)
        assertEquals(StoredPhase.PARKED, storedState(storage, unresolvedId).execution.phase)
        assertEquals(StoredPhase.RETIRED, storedState(storage, healthyId).execution.phase)

        val reopened = openParkingEngine(storage, mutations, FakeBackend())
        assertEquals(unresolvedId, reopened.deadLetters().single().mutationId)
        assertEquals(emptyList(), reopened.pendingWrites())
    }

    @Test
    fun unknownPreAckValueCodecVersion_parksWithoutTransport() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        seedParkingStorage(
            storage,
            ParkingSeed(
                sequence = 1L,
                canonicalId = "unknown-value-codec",
                mutationId = "unknown-value-codec",
                mutatorId = mutations.append.id,
                mutatorVersion = 1,
                argsBlob = "+mine".encodeToByteArray(),
                phase = StoredPhase.READY,
                valueCodecVersion = 99,
            ),
        )
        val codec = StrictVersionOneStringCodec()
        val backend = FakeBackend()
        val engine = openParkingEngine(storage, mutations, backend, valueCodec = codec)

        assertEquals(listOf("unknown-value-codec"), engine.pendingWrites().map { it.mutationId })
        assertTrue(99 in codec.decodeVersions)
        engine.drain()

        assertEquals(emptyList(), backend.receivedPushes)
        val dead = engine.deadLetters().single()
        assertEquals(MutationFailureKind.CODEC, dead.failure.kind)
        assertEquals("value-codec-pre-ack", dead.failure.detail)
        val state = storedState(storage, dead.mutationId)
        assertEquals(StoredPhase.PARKED, state.execution.phase)
        assertNotNull(state.execution.activeFailureId)
        assertEquals(state.execution.activeFailureId, state.activeFailure?.failureId)
    }

    @Test
    fun unknownMutatorOrArgsVersion_parksWithoutTransport() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        seedParkingStorage(
            storage,
            ParkingSeed(1L, "missing-mutator", "missing-mutator", "removed-mutator", 1, byteArrayOf()),
            ParkingSeed(
                2L,
                "unknown-args-version",
                "unknown-args-version",
                mutations.append.id,
                99,
                "+suffix".encodeToByteArray(),
            ),
        )
        val backend = FakeBackend()
        val engine = openParkingEngine(storage, mutations, backend)

        assertEquals(2, engine.pendingWrites().size)
        engine.drain()

        assertEquals(emptyList(), backend.receivedPushes)
        val dead = engine.deadLetters()
        assertEquals(2, dead.size)
        assertEquals(setOf("mutator-missing", "args-codec"), dead.map { it.failure.detail }.toSet())
        assertTrue(dead.all { it.failure.kind == MutationFailureKind.CODEC })
        assertTrue(dead.all { storedState(storage, it.mutationId).execution.phase == StoredPhase.PARKED })
    }

    @Test
    fun abandonedInflightResolverFailure_parksWithoutAdvancingAttemptFacts() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val firstBackend = FakeBackend().apply {
            pushBehavior = { _, _ -> throw CancellationException("push outcome unknown") }
        }
        val key = MutationsTestKey("abandoned-inflight")
        val first = openParkingEngine(storage, mutations, firstBackend)
        val mutationId = first.mutate(key, mutations.append, "+mine")
        assertFailsWith<CancellationException> { first.drain(key) }
        val before = storedState(storage, mutationId)
        val attemptBefore = assertNotNull(before.attempt).snapshot()
        assertEquals(StoredPhase.INFLIGHT, before.execution.phase)
        assertEquals(0, before.execution.attempt)
        assertNull(before.execution.lastAttemptAt)
        assertEquals(1, firstBackend.receivedPushes.size)

        val replayBackend = FakeBackend()
        val reopened =
            openParkingEngine(
                storage,
                mutations,
                replayBackend,
                resolver = MutationKeyResolver { throw IllegalStateException("resolver cache was lost") },
            )
        reopened.drain()

        assertEquals(emptyList(), replayBackend.receivedPushes)
        val after = storedState(storage, mutationId)
        assertEquals(StoredPhase.PARKED, after.execution.phase)
        assertEquals(before.execution.currentGeneration, after.execution.currentGeneration)
        assertEquals(before.execution.attempt, after.execution.attempt)
        assertEquals(before.execution.lastAttemptAt, after.execution.lastAttemptAt)
        assertEquals(attemptBefore, assertNotNull(after.attempt).snapshot())
        assertEquals(MutationFailureKind.IDENTITY, assertNotNull(after.activeFailure).kind)
        val dead = reopened.deadLetters().single()
        assertEquals(1, dead.generation)
        assertEquals(0, dead.attempts)

        val twiceReopened = openParkingEngine(storage, mutations, FakeBackend())
        assertEquals(mutationId, twiceReopened.deadLetters().single().mutationId)
    }

    @Test
    fun declinedHead_blocksOnlyItsSameEffectiveKeySuffix() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val declined = MutationsTestKey("declined")
        val healthy = MutationsTestKey("decline-independent")
        val bases = mutableMapOf(healthy.canonicalId() to "healthy")
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                baseReader = { key -> bases[key.canonicalId()] },
                handle = MapBackedParkingHandle(bases),
            )
        val head = engine.mutate(declined, mutations.strictUpdate, "+head")
        val suffix = engine.mutate(declined, mutations.append, "+suffix")
        engine.mutate(healthy, mutations.append, "+ok")

        engine.drain()

        assertEquals(listOf("healthy+ok"), backend.pushedValues)
        assertEquals(listOf(head, suffix), engine.pending(declined).map { it.mutationId })
        assertEquals(emptyList(), engine.deadLetters())

        bases[declined.canonicalId()] = "confirmed"
        engine.drain(declined)

        assertEquals(listOf("healthy+ok", "confirmed+head", "confirmed+head+suffix"), backend.pushedValues)
        assertEquals(emptyList(), engine.pending(declined))
    }

    @Test
    fun parkedHead_isRemovedFromProjection_andSameKeySuffixRebasesAndPushes() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("park-and-rebase")
        var resolverFails = true
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                resolver = MutationKeyResolver { identity ->
                    if (resolverFails) throw IllegalStateException("temporarily unresolved")
                    MutationsTestKeyResolver.resolve(identity)
                },
            )
        val head = engine.mutate(key, mutations.append, "+parked")
        val suffix = engine.mutate(key, mutations.append, "+suffix")
        assertEquals("base+parked+suffix", engine.overlay.apply(key, "base"))
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(head, engine.deadLetters().single().mutationId)
        assertEquals(listOf(suffix), engine.pending(key).map { it.mutationId })
        assertEquals("base+suffix", engine.overlay.apply(key, "base"))
        assertEquals(emptyList(), backend.receivedPushes)

        resolverFails = false
        engine.drain(key)
        assertEquals(listOf("base+suffix"), backend.pushedValues)
        assertEquals(emptyList(), engine.pending(key))
    }

    @Test
    fun parkedSequence_pinsRetiredPrefix_butNotSameKeyExecution() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("parked-prefix")
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                resolver = MutationKeyResolver { throw IllegalStateException("park first sequence") },
            )
        val parked = engine.mutate(key, mutations.append, "+parked")
        val later = engine.mutate(key, mutations.append, "+later")
        engine.clearLiveKeyCache()
        engine.drain()
        engine.drain(key)

        assertEquals(StoredPhase.PARKED, storedState(storage, parked).execution.phase)
        assertEquals(StoredPhase.RETIRED, storedState(storage, later).execution.phase)
        assertEquals(0L, storage.transaction { assertNotNull(it.client(PARKING_CLIENT_ID)).retiredThroughSequence })
        assertEquals(0L, backend.receivedPushes.single().retiredThroughSequence)
        assertEquals(listOf("base+later"), backend.pushedValues)
    }

    @Test
    fun parkedSequence_isNeverRetriedInPlace() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("never-retry-parked")
        var resolverFails = true
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                resolver = MutationKeyResolver { identity ->
                    if (resolverFails) throw IllegalStateException("park it")
                    MutationsTestKeyResolver.resolve(identity)
                },
            )
        val parked = engine.mutate(key, mutations.append, "+old")
        engine.clearLiveKeyCache()
        engine.drain()
        val original = storedState(storage, parked)
        resolverFails = false

        engine.drain()
        engine.drain(key)
        assertEquals(emptyList(), backend.receivedPushes)
        assertEquals(1, storage.transaction { it.failures(PARKING_CLIENT_ID).size })

        val compensation = engine.mutate(key, mutations.append, "+replacement")
        engine.drain(key)

        assertNotEquals(parked, compensation)
        assertEquals(listOf("base+replacement"), backend.pushedValues)
        val unchanged = storedState(storage, parked)
        assertEquals(StoredPhase.PARKED, unchanged.execution.phase)
        assertEquals(original.execution.activeFailureId, unchanged.execution.activeFailureId)
        assertEquals(1, storage.transaction { it.failures(PARKING_CLIENT_ID).size })
    }

    @Test
    fun terminalExecutionFailureProducesOneDeadLetter() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val clock = TestWallClock(startEpochMillis = 4_242L)
        val rawMessage = "terminal\tfailure\u0007" + "x".repeat(2_000) + "\nat stack(File.kt:1)"
        val engine =
            openParkingEngine(
                storage,
                mutations,
                FakeBackend(),
                resolver = MutationKeyResolver { throw IllegalStateException(rawMessage) },
                wallClock = clock,
            )
        val key = MutationsTestKey("one-dead-letter")
        val mutationId = engine.mutate(key, mutations.append, "+never")
        engine.clearLiveKeyCache()

        engine.drain()
        engine.drain()
        engine.drain(key)

        val dead = engine.deadLetters().single()
        assertEquals(mutationId, dead.mutationId)
        assertEquals(MutationFailureKind.IDENTITY, dead.failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_THROW, dead.failure.detail)
        assertTrue(dead.failure.detail.encodeToByteArray().size <= 128)
        assertTrue(dead.failure.message.encodeToByteArray().size <= 1_024)
        assertTrue(dead.failure.message.none(Char::isISOControl))
        assertFalse(dead.failure.message.contains("at stack"))
        assertFalse(dead.failure.message.contains("IllegalStateException"))
        assertEquals(4_242L, dead.failure.occurredAtEpochMillis)
        assertEquals(1, storage.transaction { it.failures(PARKING_CLIENT_ID).size })

        val reopened = openParkingEngine(storage, mutations, FakeBackend())
        val durable = reopened.deadLetters().single()
        assertEquals(dead.failure.detail, durable.failure.detail)
        assertEquals(dead.failure.message, durable.failure.message)
        assertEquals(dead.failure.occurredAtEpochMillis, durable.failure.occurredAtEpochMillis)
    }

    @Test
    fun projectionThrowableEmitsExactEphemeralCauseAndNormalizedDurableFailure() = runTest {
        val exact = IllegalStateException("projection\tfailure\nprivate stack")
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations(projectionFailure = exact)
        val backend = FakeBackend()
        val engine = openParkingEngine(storage, mutations, backend)
        val key = MutationsTestKey("projection-park")
        val mutationId = engine.mutate(key, mutations.hostile, "ignored")

        engine.drain(key)

        val poisoned = engine.poisoned.replayCache.single()
        assertEquals(mutationId, poisoned.mutationId)
        assertSame(exact, poisoned.failure)
        assertEquals(emptyList(), backend.receivedPushes)
        assertEquals(emptyList(), engine.pending(key))
        val dead = engine.deadLetters().single()
        assertEquals(MutationFailureKind.PROJECTION, dead.failure.kind)
        assertTrue(dead.failure.detail.encodeToByteArray().size <= 128)
        assertTrue(dead.failure.message.encodeToByteArray().size <= 1_024)
        assertTrue(dead.failure.message.none(Char::isISOControl))
        assertFalse(dead.failure.message.contains("private stack"))

        val reopened = openParkingEngine(storage, mutations, FakeBackend())
        assertEquals(mutationId, reopened.deadLetters().single().mutationId)
        assertTrue(reopened.poisoned.replayCache.isEmpty())
    }

    @Test
    fun globalDrainContinuesPastEveryParkedIdentity() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        seedParkingStorage(
            storage,
            ParkingSeed(1L, "codec-park", "codec-park", "missing-mutator", 1, byteArrayOf()),
            ParkingSeed(
                2L,
                "identity-park",
                "identity-park",
                mutations.append.id,
                1,
                "+identity".encodeToByteArray(),
            ),
            ParkingSeed(
                3L,
                "healthy-terminal",
                "healthy-terminal",
                mutations.append.id,
                1,
                "+ok".encodeToByteArray(),
            ),
        )
        val backend = FakeBackend()
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                resolver = MutationKeyResolver { identity ->
                    if (identity.canonicalId == "identity-park") {
                        throw IllegalStateException("identity cannot be reconstructed")
                    }
                    MutationsTestKeyResolver.resolve(identity)
                },
            )

        engine.drain()

        assertEquals(listOf("healthy-terminal"), backend.receivedPushes.map { it.identity.canonicalId })
        assertEquals(listOf("base+ok"), backend.pushedValues)
        assertEquals(
            setOf(MutationFailureKind.CODEC, MutationFailureKind.IDENTITY),
            engine.deadLetters().map { it.failure.kind }.toSet(),
        )
        assertEquals(2, engine.deadLetters().size)
        assertEquals(emptyList(), engine.pendingWrites())
        assertEquals(StoredPhase.RETIRED, storedState(storage, "healthy-terminal").execution.phase)
    }

    @Test
    fun postAckFailureNeverCreatesDeadLetter() = runTest {
        val errors = mutableListOf<String>()
        for (failureMode in ParkingPostAckFailureMode.entries) {
            try {
                assertPostAckFailureHasNoDeadLetter(failureMode)
            } catch (failure: AssertionError) {
                errors += "${failureMode.name}: ${failure.message}"
            }
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun globalDrainContinuesPastRetryablePostAckFailure() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val backend = FakeBackend()
        val blocked =
            MutationsTestKey(
                "global-post-ack-blocked",
                StoreNamespace("global-post-ack-blocked-namespace"),
            )
        val healthy =
            MutationsTestKey(
                "global-post-ack-healthy",
                StoreNamespace("global-post-ack-healthy-namespace"),
            )
        val handle = ScriptedParkingHandle()
        val resolver =
            MutationKeyResolver<MutationsTestKey> { identity ->
                MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
            }
        val engine =
            openParkingEngine(
                storage = storage,
                mutations = mutations,
                backend = backend,
                resolver = resolver,
                handle = handle,
            )
        val blockedId = engine.mutate(blocked, mutations.append, "+blocked")
        val healthyId = engine.mutate(healthy, mutations.append, "+healthy")
        handle.remainingApplyFailures[blocked.identity()] = 1
        assertNotNull(captureParkingFailure { engine.drain(blocked) })
        assertEquals(StoredPhase.ACKED, storedState(storage, blockedId).execution.phase)
        val pushCountBeforeGlobal = backend.receivedPushes.size
        handle.applyAttempts.clear()
        handle.remainingApplyFailures[blocked.identity()] = 1
        engine.clearLiveKeyCache()

        val exposed = captureParkingFailure { engine.drain() }
        val blockedState = storedState(storage, blockedId)
        val healthyState = storedState(storage, healthyId)
        val deadLetters = engine.deadLetters()
        val errors = mutableListOf<String>()
        expectParking(errors, "global drain returns normally") { assertNull(exposed) }
        expectParking(errors, "retryable post-ack owner stays ACKED") {
            assertEquals(StoredPhase.ACKED, blockedState.execution.phase)
        }
        expectParking(errors, "different namespace completes") {
            assertEquals(StoredPhase.RETIRED, healthyState.execution.phase)
        }
        expectParking(errors, "each namespace starts authority work once") {
            assertEquals(
                listOf(blocked.identity(), healthy.identity()),
                backend.receivedPushes.map { push ->
                    KeyIdentity(push.identity.namespace, push.identity.canonicalId)
                },
            )
            assertEquals(pushCountBeforeGlobal + 1, backend.receivedPushes.size)
            assertEquals(listOf(blocked.identity(), healthy.identity()), handle.applyAttempts)
        }
        expectParking(errors, "post-ack failure never parks") {
            assertTrue(deadLetters.isEmpty())
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun parkingCancellationAfterCommit_stillPublishesOverlayRevisionAndRebasesSuffix() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ParkingMutations()
        val key = MutationsTestKey("cancelled-park")
        seedParkingStorage(
            storage,
            ParkingSeed(
                1L,
                key.canonicalId(),
                "cancelled-park-head",
                mutations.append.id,
                1,
                "+parked".encodeToByteArray(),
            ),
            ParkingSeed(
                2L,
                key.canonicalId(),
                "cancelled-park-suffix",
                mutations.append.id,
                1,
                "+suffix".encodeToByteArray(),
            ),
        )
        val backend = FakeBackend()
        val engine =
            openParkingEngine(
                storage,
                mutations,
                backend,
                resolver = MutationKeyResolver { identity ->
                    if (identity.canonicalId == key.canonicalId()) {
                        throw IllegalStateException("scripted resolver park")
                    }
                    MutationsTestKeyResolver.resolve(identity)
                },
            )
        val blocker = MutationsTestKey("parking-signal-blocker")
        val blockerObserved = CompletableDeferred<String>()
        val parkedObserved = CompletableDeferred<String>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.changes.collect { changed ->
                    when (changed.canonicalId()) {
                        blocker.canonicalId() -> blockerObserved.complete(changed.canonicalId())
                        key.canonicalId() -> parkedObserved.complete(changed.canonicalId())
                    }
                }
            }

        try {
            engine.mutate(blocker, mutations.append, "+blocker")
            assertEquals("base+parked+suffix", engine.overlay.apply(key, "base"))

            val cancelledDrain =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.drain()
                }
            assertFalse(cancelledDrain.isCompleted)

            assertEquals("cancelled-park-head", engine.deadLetters().single().mutationId)
            assertEquals(
                listOf("cancelled-park-suffix"),
                engine.pending(key).map { pending -> pending.mutationId },
            )
            assertEquals("base+suffix", engine.overlay.apply(key, "base"))
            assertEquals(emptyList(), backend.receivedPushes)

            cancelledDrain.cancel()
            testScheduler.runCurrent()
            cancelledDrain.join()

            assertTrue(cancelledDrain.isCancelled)
            assertEquals(blocker.canonicalId(), blockerObserved.await())
            assertTrue(
                parkedObserved.isCompleted,
                "a committed park must retain its identity revision across cancellation",
            )
            assertEquals(key.canonicalId(), parkedObserved.await())
            assertEquals("base+suffix", engine.overlay.apply(key, "base"))
        } finally {
            collector.cancelAndJoin()
        }
    }
}

private const val PARKING_CLIENT_ID: String = "client-0"

private class ParkingMutations(
    projectionFailure: Throwable? = null,
    staleKeys: Set<MutationsTestKey> = emptySet(),
) {
    private val argsCodec = StrictVersionOneStringCodec()
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    lateinit var strictUpdate: MutatorRef<MutationsTestKey, String, String>
    lateinit var hostile: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            append =
                upsert(
                    id = "parking-append",
                    version = 1,
                    codec = argsCodec,
                    stales =
                        if (staleKeys.isEmpty()) {
                            noStales()
                        } else {
                            typedStales<String>(keys = staleKeys)
                        },
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
            strictUpdate =
                update(
                    id = "parking-strict-update",
                    version = 1,
                    codec = argsCodec,
                    stales = noStales(),
                ) { current, suffix -> current + suffix }
            hostile =
                mutator(
                    id = "parking-hostile",
                    version = 1,
                    codec = argsCodec,
                    stales = noStales(),
                ) { base, _ ->
                    projectionFailure?.let { throw it }
                    base
                }
        }
}

private class StrictVersionOneStringCodec : MutationCodec<String> {
    val decodeVersions: MutableList<Int> = mutableListOf()

    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): String {
        decodeVersions += version
        require(version == 1) { "Only codec version 1 is available; was $version." }
        return bytes.decodeToString()
    }
}

private object ParkingNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(key: MutationsTestKey, value: String) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private class MapBackedParkingHandle(
    private val values: MutableMap<String, String>,
) : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(key: MutationsTestKey, value: String) {
        values[key.canonicalId()] = value
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private class ScriptedParkingHandle : StoreWriteHandle<MutationsTestKey, String> {
    val remainingApplyFailures = mutableMapOf<KeyIdentity, Int>()
    val remainingMarkStaleFailures = mutableMapOf<KeyIdentity, Int>()
    val applyAttempts = mutableListOf<KeyIdentity>()
    val markStaleAttempts = mutableListOf<KeyIdentity>()

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        val identity = key.identity()
        applyAttempts += identity
        val remaining = remainingApplyFailures[identity] ?: 0
        if (remaining > 0) {
            remainingApplyFailures[identity] = remaining - 1
            throw IllegalStateException("scripted adoption failure")
        }
    }

    override suspend fun markStale(key: MutationsTestKey) {
        val identity = key.identity()
        markStaleAttempts += identity
        val remaining = remainingMarkStaleFailures[identity] ?: 0
        if (remaining > 0) {
            remainingMarkStaleFailures[identity] = remaining - 1
            throw IllegalStateException("scripted effect failure")
        }
    }

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private fun openParkingEngine(
    storage: MutationJournalStorage,
    mutations: ParkingMutations,
    backend: FakeBackend,
    resolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    baseReader: suspend (MutationsTestKey) -> String? = { "base" },
    valueCodec: MutationCodec<String> = StrictVersionOneStringCodec(),
    wallClock: WallClock = TestWallClock(),
    handle: StoreWriteHandle<MutationsTestKey, String> = ParkingNoopHandle,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = PARKING_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = resolver,
        valueCodecVersion = 1,
        valueCodec = valueCodec,
        baseReader = baseReader,
        wallClock = wallClock,
        clientId = PARKING_CLIENT_ID,
    ).also { it.bind(handle) }
}

private enum class ParkingPostAckFailureMode(
    val expectedPhase: StoredPhase,
) {
    ADOPTION(StoredPhase.ACKED),
    EFFECT(StoredPhase.EFFECTS_PENDING),
    PERSISTENCE(StoredPhase.EFFECTS_PENDING),
}

private suspend fun assertPostAckFailureHasNoDeadLetter(
    failureMode: ParkingPostAckFailureMode,
) {
    val backing = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(backing)
    val key = MutationsTestKey("post-ack-${failureMode.name.lowercase()}")
    val effectTarget = MutationsTestKey("post-ack-${failureMode.name.lowercase()}-effect")
    val mutations =
        ParkingMutations(
            staleKeys = setOf(effectTarget).takeIf {
                failureMode == ParkingPostAckFailureMode.EFFECT
            }.orEmpty(),
        )
    val backend =
        FakeBackend().apply {
            retireBehavior = {
                MutationRetirementAck(confirmedThroughSequence = 0L)
            }
        }
    val firstHandle = ScriptedParkingHandle()
    configureParkingFailure(failureMode, key, effectTarget, storage, firstHandle)
    val first = openParkingEngine(storage, mutations, backend, handle = firstHandle)
    val mutationId = first.mutate(key, mutations.append, "+value")

    assertNotNull(captureParkingFailure { first.drain(key) })
    assertEquals(failureMode.expectedPhase, storedState(backing, mutationId).execution.phase)
    assertNull(storedState(backing, mutationId).execution.activeFailureId)
    assertTrue(first.deadLetters().isEmpty())
    val pushCount = backend.receivedPushes.size

    val reopenedHandle = ScriptedParkingHandle()
    val reopened = openParkingEngine(storage, mutations, backend, handle = reopenedHandle)
    assertEquals(failureMode.expectedPhase, storedState(backing, mutationId).execution.phase)
    assertTrue(reopened.deadLetters().isEmpty())
    assertNull(captureParkingFailure { reopened.drain(key) })
    assertEquals(StoredPhase.RETIRED, storedState(backing, mutationId).execution.phase)
    assertEquals(pushCount, backend.receivedPushes.size)
    assertTrue(reopened.deadLetters().isEmpty())
}

private fun configureParkingFailure(
    failureMode: ParkingPostAckFailureMode,
    key: MutationsTestKey,
    effectTarget: MutationsTestKey,
    storage: FailPointJournalStorage,
    handle: ScriptedParkingHandle,
) {
    when (failureMode) {
        ParkingPostAckFailureMode.ADOPTION ->
            handle.remainingApplyFailures[key.identity()] = 1
        ParkingPostAckFailureMode.EFFECT ->
            handle.remainingMarkStaleFailures[effectTarget.identity()] = 1
        ParkingPostAckFailureMode.PERSISTENCE ->
            storage.armFailTransaction(JournalFailPointBoundary.FINALIZATION)
    }
}

private suspend fun captureParkingFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        failure
    }

private fun expectParking(
    errors: MutableList<String>,
    label: String,
    assertion: () -> Unit,
) {
    try {
        assertion()
    } catch (failure: AssertionError) {
        errors += "$label: ${failure.message}"
    }
}

private data class ParkingSeed(
    val sequence: Long,
    val canonicalId: String,
    val mutationId: String,
    val mutatorId: String,
    val mutatorVersion: Int,
    val argsBlob: ByteArray,
    val phase: StoredPhase = StoredPhase.UNPREPARED,
    val valueCodecVersion: Int = 1,
)

private suspend fun seedParkingStorage(
    storage: InMemoryMutationJournalStorage,
    vararg seeds: ParkingSeed,
) {
    require(seeds.isNotEmpty())
    val ordered = seeds.sortedBy { it.sequence }
    storage.transaction { transaction ->
        transaction.insertClient(
            MutationClientRecord(1, PARKING_CLIENT_ID, 0L, 0L, 0L, 100L),
        )
        transaction.advanceClient(
            MutationClientRecord(1, PARKING_CLIENT_ID, ordered.last().sequence, 0L, 0L, 100L),
        )
        ordered.forEach { seed ->
            transaction.insertIntent(
                recordVersion = 1,
                clientId = PARKING_CLIENT_ID,
                clientSequence = seed.sequence,
                mutationId = seed.mutationId,
                namespace = "mutations",
                canonicalId = seed.canonicalId,
                mutatorId = seed.mutatorId,
                mutatorVersion = seed.mutatorVersion,
                argsBlob = seed.argsBlob,
                idempotencyRoot = "$PARKING_CLIENT_ID:${seed.sequence}",
                createdAt = 100L + seed.sequence,
            )
            val unprepared =
                MutationExecutionRecord(
                    PARKING_CLIENT_ID,
                    seed.sequence,
                    StoredPhase.UNPREPARED,
                    0,
                    0,
                    null,
                    null,
                    null,
                )
            transaction.insertExecution(unprepared)
            if (seed.phase != StoredPhase.UNPREPARED) {
                transaction.insertAttempt(parkingAttempt(seed))
                val ready =
                    MutationExecutionRecord(
                        PARKING_CLIENT_ID,
                        seed.sequence,
                        StoredPhase.READY,
                        1,
                        0,
                        null,
                        null,
                        null,
                    )
                transaction.advanceExecution(ready)
                if (seed.phase == StoredPhase.INFLIGHT) {
                    transaction.advanceExecution(
                        MutationExecutionRecord(
                            PARKING_CLIENT_ID,
                            seed.sequence,
                            StoredPhase.INFLIGHT,
                            1,
                            0,
                            null,
                            null,
                            null,
                        ),
                    )
                } else {
                    require(seed.phase == StoredPhase.READY)
                }
            }
        }
    }
}

private fun parkingAttempt(seed: ParkingSeed): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = PARKING_CLIENT_ID,
        clientSequence = seed.sequence,
        generation = 1,
        effectiveNamespace = "mutations",
        effectiveCanonicalId = seed.canonicalId,
        valueCodecVersion = seed.valueCodecVersion,
        basePresence = MutationPresenceState.PRESENT,
        baseBlob = "base".encodeToByteArray(),
        minePresence = MutationPresenceState.PRESENT,
        mineBlob = "mine-${seed.sequence}".encodeToByteArray(),
        preconditionMetaPresent = false,
        preconditionWrittenAt = null,
        preconditionEtag = null,
        advertisedRetiredThroughSequence = 0L,
        generationIdempotencyKey = "$PARKING_CLIENT_ID:${seed.sequence}:g1",
        preparedAt = 200L + seed.sequence,
        conflictMetaPresent = null,
        conflictWrittenAt = null,
        conflictEtag = null,
        conflictReceivedAt = null,
    )

private data class StoredState(
    val execution: MutationExecutionRecord,
    val attempt: MutationAttemptRecord?,
    val activeFailure: MutationFailureRecord?,
)

private suspend fun storedState(
    storage: InMemoryMutationJournalStorage,
    mutationId: String,
): StoredState =
    storage.transaction { transaction ->
        val intent = transaction.intents(PARKING_CLIENT_ID).single { it.mutationId == mutationId }
        val execution =
            transaction.executions(PARKING_CLIENT_ID).single {
                it.clientSequence == intent.clientSequence
            }
        StoredState(
            execution = execution,
            attempt =
                transaction.attempts(PARKING_CLIENT_ID).singleOrNull {
                    it.clientSequence == intent.clientSequence &&
                        it.generation == execution.currentGeneration
                },
            activeFailure =
                execution.activeFailureId?.let { failureId ->
                    transaction.failures(PARKING_CLIENT_ID).single { it.failureId == failureId }
                },
        )
    }

private data class ParkingAttemptSnapshot(
    val generation: Int,
    val effectiveNamespace: String,
    val effectiveCanonicalId: String,
    val valueCodecVersion: Int,
    val basePresence: MutationPresenceState,
    val baseBlob: List<Byte>?,
    val minePresence: MutationPresenceState,
    val mineBlob: List<Byte>?,
    val advertisedRetiredThroughSequence: Long,
    val generationIdempotencyKey: String,
    val preparedAt: Long,
)

private fun MutationAttemptRecord.snapshot(): ParkingAttemptSnapshot =
    ParkingAttemptSnapshot(
        generation,
        effectiveNamespace,
        effectiveCanonicalId,
        valueCodecVersion,
        basePresence,
        baseBlob?.toList(),
        minePresence,
        mineBlob?.toList(),
        advertisedRetiredThroughSequence,
        generationIdempotencyKey,
        preparedAt,
    )

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
