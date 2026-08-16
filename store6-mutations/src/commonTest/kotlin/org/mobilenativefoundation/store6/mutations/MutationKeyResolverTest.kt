@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The required resolver is global drain's correctness path. Every test drives the engine's own
 * resolution — cache cleared where reconstruction must be forced — and proves exact-pair
 * validation happens before any transport work. Durable parking of an unresolved pre-ack
 * identity is covered by `MutationDrainParkingTest`, restart hydration by
 * `MutationJournalContractTest`.
 */
class MutationKeyResolverTest {
    @Test
    fun exactNamespaceAndCanonicalId_isAccepted() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val resolverCalls = mutableListOf<Pair<String, String>>()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverCalls += identity.namespace to identity.canonicalId
                        ProbeKey(identity.namespace, identity.canonicalId)
                    },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        engine.mutate(ProbeKey("probe", "entity-1"), probe.ref, "next")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(listOf("probe" to "entity-1"), resolverCalls)
        assertEquals(1, server.pushes.size)
        assertEquals("probe", server.pushes.single().identity.namespace)
        assertEquals("entity-1", server.pushes.single().identity.canonicalId)
        assertEquals(emptyList(), engine.pending(ProbeKey("probe", "entity-1")))
        assertEquals(emptyList(), engine.drainFailuresForInspection())
    }

    @Test
    fun nullResolution_isRejectedBeforeTransport() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver = MutationKeyResolver { null },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-null")
        val mutationId = engine.mutate(key, probe.ref, "next")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(emptyList(), server.pushes)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_NULL, failure.detail)
        assertTrue(failure.message.contains("entity-null"))
    }

    @Test
    fun throwingResolution_isNormalizedWithoutRetainingThrowable() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver =
                    MutationKeyResolver {
                        throw IllegalStateException(
                            "resolver blew up\n\tat frame.one(Resolver.kt:1)\n\tat frame.two",
                        )
                    },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-throw")
        val mutationId = engine.mutate(key, probe.ref, "next")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(emptyList(), server.pushes)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_THROW, failure.detail)
        // First line only, controls stripped: the carrier holds sanitized strings, never the
        // original Throwable, its frames, or its cause chain (D3/D14).
        assertEquals("resolver blew up", failure.message)
        assertFalse(failure.message.contains('\n'))
        assertFalse(failure.message.any(Char::isISOControl))
    }

    @Test
    fun resolverCancellation_rethrowsWithoutRecordingFailure() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver = MutationKeyResolver { throw CancellationException("resolver cancelled") },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-cancel")
        val mutationId = engine.mutate(key, probe.ref, "next")
        engine.clearLiveKeyCache()

        val failure =
            assertFailsWith<CancellationException> {
                engine.drain()
            }

        assertEquals("resolver cancelled", failure.message)
        assertEquals(emptyList(), server.pushes)
        assertEquals(emptyList(), engine.drainFailuresForInspection())
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
    }

    @Test
    fun namespaceMismatch_isRejected() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver = MutationKeyResolver { identity -> ProbeKey("other", identity.canonicalId) },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-2")
        val mutationId = engine.mutate(key, probe.ref, "next")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(emptyList(), server.pushes)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH, failure.detail)
    }

    @Test
    fun canonicalIdMismatch_isRejected() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver = MutationKeyResolver { identity -> ProbeKey(identity.namespace, "different") },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-3")
        val mutationId = engine.mutate(key, probe.ref, "next")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(emptyList(), server.pushes)
        assertEquals(
            listOf(mutationId),
            engine.pending(key).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH, failure.detail)
    }

    @Test
    fun cachedKey_isRevalidatedBeforeReuse() = runTest {
        val probe = ProbeMutation()
        val server = ProbeServer()
        val resolverCalls = mutableListOf<Pair<String, String>>()
        val engine =
            MutationEngine(
                registry = probe.registry,
                server = server,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverCalls += identity.namespace to identity.canonicalId
                        ProbeKey(identity.namespace, identity.canonicalId)
                    },
                baseReader = { "base" },
            )
        engine.bind(ProbeNoopHandle)
        val key = ProbeKey("probe", "entity-4")
        engine.mutate(key, probe.ref, "next")
        // The enqueue seeded the live cache with this exact key object. Drift its canonical id:
        // the cached K no longer reproduces the durable pair, so reuse without revalidation
        // would address the wrong entity.
        key.id = "drifted"

        engine.drain()

        // Revalidation rejected the drifted cache entry and the resolver reconstructed the key
        // from the durable identity pair; the push addressed the original identity.
        assertEquals(listOf("probe" to "entity-4"), resolverCalls)
        assertEquals(1, server.pushes.size)
        assertEquals("entity-4", server.pushes.single().identity.canonicalId)
        assertEquals(emptyList(), engine.drainFailuresForInspection())
    }
}

/** Single-file probe key with a configurable namespace and a deliberately mutable id. */
private class ProbeKey(
    private val namespaceValue: String,
    var id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(namespaceValue)

    override fun canonicalId(): String = id
}

private class ProbeMutation {
    lateinit var ref: MutatorRef<ProbeKey, String, String>
    val registry: MutatorRegistry<ProbeKey, String> =
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

private class ProbeServer : MutationServer<ProbeKey, String> {
    val pushes = mutableListOf<MutationPush<ProbeKey, String>>()

    override suspend fun push(request: MutationPush<ProbeKey, String>): MutationAck<ProbeKey, String> {
        pushes += request
        return when (val mine = request.mine) {
            is MutationPresence.Present ->
                MutationPresentAck(
                    authoritative = mine.value,
                    etag = "etag-${pushes.size}",
                    canonicalKey = null,
                )
            MutationPresence.Absent -> MutationAbsentAck(etag = "etag-${pushes.size}")
        }
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

private object ProbeNoopHandle : StoreWriteHandle<ProbeKey, String> {
    override suspend fun apply(
        key: ProbeKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: ProbeKey) = Unit

    override suspend fun confirmFresh(
        key: ProbeKey,
        etag: String?,
    ) = Unit
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
