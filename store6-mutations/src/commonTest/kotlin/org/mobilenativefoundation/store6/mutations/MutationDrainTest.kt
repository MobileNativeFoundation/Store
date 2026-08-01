@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * R1-17's 021 slice: `drain(key)` and `drain()` are idempotent, scheduler-agnostic foreground
 * passes (D12) and the resolver — not any live key map — is global drain's correctness path
 * (D14). Restart enumeration is 022's `MutationJournalContractTest`; parked-identity
 * continuation, retryable post-ack continuation, and the one-attempt-per-phase rule are 023's.
 */
class MutationDrainTest {
    @Test
    fun globalDrain_reconstructsKeyAfterLiveKeyCacheIsCleared() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val resolverCalls = mutableListOf<Pair<String, String>>()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverCalls += identity.namespace to identity.canonicalId
                        MutationsTestKeyResolver.resolve(identity)
                    },
                baseReader = { "base" },
            )
        engine.bind(DrainNoopHandle)
        val key = MutationsTestKey("reconstructed")
        engine.mutate(key, mutation.ref, "recovered-value")

        // The live key map is cache only: with every cached resolution dropped, the durable
        // journal identity plus the resolver must reconstruct K or the drain cannot proceed.
        engine.clearLiveKeyCache()
        engine.drain()

        assertEquals(listOf("mutations" to "reconstructed"), resolverCalls)
        assertEquals(listOf("recovered-value"), backend.pushedValues)
        assertEquals("reconstructed", backend.receivedPushes.single().identity.canonicalId)
        assertEquals(emptyList(), engine.pending(key))
        assertEquals(emptyList(), engine.drainFailuresForInspection())
    }

    @Test
    fun keyedDrain_isIdempotentForegroundPass() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("keyed-idempotent")
        val untouched = MutationsTestKey("keyed-untouched")
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
            val fetchesBeforeDrain = backend.fetchCount
            users.mutate(key, mutation.ref, "pending")

            users.drain(key)

            assertEquals(listOf("pending"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(key))

            // A second keyed pass over retired work and a pass over a key that never had work
            // are both no-ops: no push, no fetch, no invented failure (D12/D14).
            users.drain(key)
            users.drain(untouched)

            assertEquals(listOf("pending"), backend.pushedValues)
            assertEquals(fetchesBeforeDrain, backend.fetchCount)
            assertEquals(emptyList(), users.pending(key))
        } finally {
            users.close()
        }
    }

    @Test
    fun globalDrain_processesMultipleIdentitiesDeterministically() = runTest {
        val mutation = DrainAppendMutation()
        val backend = FakeBackend()
        val first = MutationsTestKey("multi-a")
        val second = MutationsTestKey("multi-b")
        val third = MutationsTestKey("multi-c")
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
            users.mutate(first, mutation.ref, "+a1")
            users.mutate(first, mutation.ref, "+a2")
            users.mutate(second, mutation.ref, "+b")
            users.mutate(third, mutation.ref, "+c")

            users.drain()

            // Identities are processed in first-enqueue order and each identity's intents flush
            // by durable client sequence; the second first-key push rides the first's echo.
            assertEquals(
                listOf("multi-a", "multi-a", "multi-b", "multi-c"),
                backend.receivedPushes.map { push -> push.identity.canonicalId },
            )
            assertEquals(listOf("+a1", "+a1+a2", "+b", "+c"), backend.pushedValues)
            val firstKeySequences =
                backend.receivedPushes
                    .filter { push -> push.identity.canonicalId == "multi-a" }
                    .map(MutationPush<MutationsTestKey, String>::clientSequence)
            assertEquals(firstKeySequences, firstKeySequences.sorted())
            assertEquals(emptyList(), users.pendingWrites())

            // Re-running over fully retired work changes nothing.
            users.drain()
            assertEquals(4, backend.receivedPushes.size)
        } finally {
            users.close()
        }
    }

    @Test
    fun emptyGlobalDrain_isIdempotentNoOp() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
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
            users.drain()
            users.drain()

            assertEquals(emptyList(), backend.receivedPushes)
            assertEquals(0, backend.fetchCount)
            assertEquals(emptyList(), users.pendingWrites())
            assertEquals(emptyList(), users.deadLetters())
        } finally {
            users.close()
        }
    }

    @Test
    fun oneResolverFailure_doesNotBlockAnotherIdentity() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        if (identity.canonicalId == "poisoned-identity") {
                            throw IllegalStateException("cold lookup failed")
                        }
                        MutationsTestKeyResolver.resolve(identity)
                    },
                baseReader = { "base" },
            )
        engine.bind(DrainNoopHandle)
        val poisoned = MutationsTestKey("poisoned-identity")
        val healthy = MutationsTestKey("healthy-identity")
        // The failing identity enqueues first so continuation past it is what the test proves.
        val poisonedId = engine.mutate(poisoned, mutation.ref, "never-pushed")
        engine.mutate(healthy, mutation.ref, "healthy-value")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(listOf("healthy-value"), backend.pushedValues)
        assertEquals(emptyList(), engine.pending(healthy))
        assertEquals(
            listOf(poisonedId),
            engine.pending(poisoned).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_THROW, failure.detail)
        assertEquals("cold lookup failed", failure.message)
    }

    @Test
    fun declinedHead_leavesSuffixPendingAndGlobalDrainProcessesOtherIdentities() = runTest {
        lateinit var strictUpdate: MutatorRef<MutationsTestKey, String, String>
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                strictUpdate =
                    update(
                        id = "strict-update",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> value }
                rename =
                    mutator(
                        id = "rename",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { key -> if (key.canonicalId() == "declined-key") null else "base" },
            )
        engine.bind(DrainNoopHandle)
        val declined = MutationsTestKey("declined-key")
        val healthy = MutationsTestKey("healthy-key")
        // `update` over a stably absent base declines (D13): the head stays PENDING and blocks
        // only its own same-effective-key suffix.
        val declinedHead = engine.mutate(declined, strictUpdate, "never-applies")
        val blockedSuffix = engine.mutate(declined, rename, "blocked-behind-decline")
        engine.mutate(healthy, rename, "healthy-value")

        engine.drain()

        assertEquals(listOf("healthy-value"), backend.pushedValues)
        assertEquals(
            listOf(declinedHead, blockedSuffix),
            engine.pending(declined).map(PendingIntent::mutationId),
        )
        assertEquals(emptyList(), engine.pending(healthy))
        // A deliberate decline is not a failure: no normalized carrier and no poison.
        assertEquals(emptyList(), engine.drainFailuresForInspection())
        assertTrue(engine.poisoned.replayCache.isEmpty())
    }
}

private class DrainRenameMutation {
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

private class DrainAppendMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref =
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
}

private object DrainNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
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
