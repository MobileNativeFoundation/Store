@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class MutationProtocolTest {
    // R1-01 (021 slice): the server signature receives the complete library-built carrier for a
    // registered mutator. The end-to-end factory flow is strengthened at T4.3/T4.5.
    @Test
    fun serverReceivesLibraryBuiltPushWithCompleteRegisteredMutatorCarrier() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                append =
                    mutator(
                        id = "append",
                        version = 1,
                        codec = RecordingStringCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { base, suffix ->
                        when (base) {
                            is MutationPresence.Present ->
                                MutationPresence.Present(base.value + suffix)
                            MutationPresence.Absent -> null
                        }
                    }
            }
        val mine =
            registry.registrations
                .getValue(append.id)
                .project(MutationPresence.Present("base"), "-mine")
        val projectedMine = assertIs<MutationPresence.Present<String>>(mine)
        val server = RecordingMutationServer()
        val push =
            libraryBuiltPush(
                base = MutationPresence.Present("base"),
                mine = projectedMine,
            )

        server.push(push)

        assertEquals(1, server.pushes.size)
        val received = server.pushes.single()
        assertSame(push, received)
        assertEquals("mutations", received.identity.namespace)
        assertEquals("entity-1", received.identity.canonicalId)
        assertEquals("entity-1", received.key.canonicalId())
        assertEquals("client-1", received.clientId)
        assertEquals(4L, received.clientSequence)
        assertEquals(3L, received.retiredThroughSequence)
        assertEquals("mutation-9", received.mutationId)
        assertEquals(2, received.generation)
        assertEquals("idempotency-2", received.idempotencyKey)
        assertEquals(5, received.valueCodecVersion)
        assertEquals("base-mine", projectedMine.value)
    }

    // R1-03.
    @Test
    fun valueCodecRoundTrip_preservesPresentBaseMineAndAck() = runTest {
        val codec = RecordingStringCodec()

        val baseBlob = codec.encodeCopied("base-value")
        val mineBlob = codec.encodeCopied("mine-value")
        val ackBlob = codec.encodeCopied("authoritative-value")

        val base = MutationPresence.Present(codec.decodeCopied(1, baseBlob))
        val mine = MutationPresence.Present(codec.decodeCopied(1, mineBlob))
        val ack = MutationPresentAck<MutationsTestKey, String>(
            authoritative = codec.decodeCopied(1, ackBlob),
            etag = "etag-1",
            canonicalKey = null,
        )

        assertEquals("base-value", base.value)
        assertEquals("mine-value", mine.value)
        assertEquals("authoritative-value", ack.authoritative)
    }

    // R1-03.
    @Test
    fun decodeReceivesPersistedValueCodecVersion() = runTest {
        val codec = RecordingStringCodec()
        val push =
            libraryBuiltPush(
                base = MutationPresence.Present("base"),
                mine = MutationPresence.Present("mine"),
                valueCodecVersion = 7,
            )

        codec.decodeCopied(push.valueCodecVersion, "stored".encodeToByteArray())

        assertEquals(7, codec.lastDecodeVersion)
    }

    // R1-03.
    @Test
    fun valueCodecBuffers_areDefensivelyCopiedAtEncodeAndDecodeBoundaries() = runTest {
        val codec = RecordingStringCodec()

        // Encode boundary: the retained array is a copy, so a codec (or producer) mutating the
        // array it returned cannot corrupt stored bytes.
        val stored = codec.encodeCopied("payload")
        val returnedByCodec = assertIs<ByteArray>(codec.lastEncodeResult)
        assertNotSame(returnedByCodec, stored)
        returnedByCodec.fill(0)
        assertContentEquals("payload".encodeToByteArray(), stored)

        // Decode boundary: the codec receives a fresh copy, so mutating either array cannot
        // corrupt the stored retry generation.
        val decoded = codec.decodeCopied(1, stored)
        assertEquals("payload", decoded)
        val receivedByCodec = assertIs<ByteArray>(codec.lastDecodeBytes)
        assertNotSame(stored, receivedByCodec)
        receivedByCodec.fill(0)
        assertContentEquals("payload".encodeToByteArray(), stored)
    }

    // R1-05: presence, never nullable V, crosses push/candidate/ack carriers.
    @Test
    fun pushBaseAndMine_neverUseNullableValue() = runTest {
        // Compile-level: these property references type-check only while the carrier fields are
        // non-nullable MutationPresence.
        val pushBase: KProperty1<MutationPush<MutationsTestKey, String>, MutationPresence<String>> =
            MutationPush<MutationsTestKey, String>::base
        val pushMine: KProperty1<MutationPush<MutationsTestKey, String>, MutationPresence<String>> =
            MutationPush<MutationsTestKey, String>::mine
        val candidateBase:
            KProperty1<
                MutationPreconditionCandidate<MutationsTestKey, String>,
                MutationPresence<String>,
            > =
            MutationPreconditionCandidate<MutationsTestKey, String>::base
        val ackValue: KProperty1<MutationPresentAck<MutationsTestKey, String>, String> =
            MutationPresentAck<MutationsTestKey, String>::authoritative
        assertEquals("base", pushBase.name)
        assertEquals("mine", pushMine.name)
        assertEquals("base", candidateBase.name)
        assertEquals("authoritative", ackValue.name)

        // Runtime: confirmed absence is the Absent value, not null.
        val push =
            libraryBuiltPush(
                base = MutationPresence.Absent,
                mine = MutationPresence.Present("mine"),
            )
        assertSame(MutationPresence.Absent, push.base)
        assertIs<MutationPresence.Present<String>>(push.mine)
    }

    // R1-08.
    @Test
    fun pushExposesCompleteFrozenGenerationAndAuthoritativeIdentity() = runTest {
        val meta = TestMeta(writtenAtEpochMillis = 42L, etag = "etag-precondition")
        val push =
            libraryBuiltPush(
                base = MutationPresence.Present("base"),
                mine = MutationPresence.Present("mine"),
                baseMeta = meta,
            )

        assertEquals("mutations", push.identity.namespace)
        assertEquals("entity-1", push.identity.canonicalId)
        assertEquals("entity-1", push.key.canonicalId())
        assertEquals("client-1", push.clientId)
        assertEquals(4L, push.clientSequence)
        assertEquals(3L, push.retiredThroughSequence)
        assertEquals("mutation-9", push.mutationId)
        assertEquals(2, push.generation)
        assertEquals("idempotency-2", push.idempotencyKey)
        assertEquals(5, push.valueCodecVersion)
        assertEquals("base", assertIs<MutationPresence.Present<String>>(push.base).value)
        assertEquals("mine", assertIs<MutationPresence.Present<String>>(push.mine).value)
        assertEquals(42L, assertIs<StoreMeta>(push.baseMeta).writtenAtEpochMillis)
        assertEquals("etag-precondition", push.baseMeta?.etag)
    }

    // R1-08: identity is the sole server-authoritative address; the resolved key is
    // process-local adapter context whose non-identity representation may vary across retries.
    @Test
    fun pushIdentity_staysStable_whenResolvedKeyRepresentationChanges() = runTest {
        val identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1")

        fun retryWithResolvedKey(key: RepresentedKey): MutationPush<RepresentedKey, String> =
            MutationPush(
                identity = identity,
                key = key,
                clientId = "client-1",
                clientSequence = 4L,
                retiredThroughSequence = 3L,
                mutationId = "mutation-9",
                generation = 2,
                idempotencyKey = "idempotency-2",
                valueCodecVersion = 5,
                base = MutationPresence.Present("base"),
                mine = MutationPresence.Present("mine"),
                baseMeta = null,
            )

        val firstRetry = retryWithResolvedKey(RepresentedKey(id = "entity-1", revision = 1))
        val secondRetry = retryWithResolvedKey(RepresentedKey(id = "entity-1", revision = 2))

        assertSame(identity, firstRetry.identity)
        assertSame(identity, secondRetry.identity)
        assertEquals(firstRetry.identity.namespace, secondRetry.identity.namespace)
        assertEquals(firstRetry.identity.canonicalId, secondRetry.identity.canonicalId)
        // The adapter-context representation changed; the authoritative address did not.
        assertEquals(1, firstRetry.key.revision)
        assertEquals(2, secondRetry.key.revision)
        assertEquals(firstRetry.idempotencyKey, secondRetry.idempotencyKey)
    }

    // R1-08 (021 slice): the exact-pair validation the engine runs before transport; D14 fixes
    // cause == null for mismatch. The engine-path proof is strengthened at T4.4/T4.5.
    @Test
    fun resolverIdentityMismatch_failsBeforeTransport() = runTest {
        val identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "expected")
        val resolver = MutationKeyResolver<MutationsTestKey> { MutationsTestKey("different") }
        val server = RecordingMutationServer()

        val resolved = resolver.resolve(identity)
        val failure =
            assertFailsWith<IllegalStateException> {
                requireResolvedKey(identity, resolved)
            }

        assertNull(failure.cause)
        assertTrue(server.pushes.isEmpty())
        assertTrue(server.retirements.isEmpty())
    }

    // R1-08, strengthened to the engine path at T4.5 (Surface NOTES §3.11): the ENGINE rebuilds
    // every retry of one semantic generation from defensively copied blobs. A server that
    // mutates the delivered base/mine carrier cannot alter the next reconstruction, and the
    // shared base instance the engine read stays untouched. Durable INFLIGHT exact-replay is
    // R1-08's 023 proof.
    @Test
    fun semanticRetry_rebuildsEveryPushFieldFromDefensiveCopies() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, MutableValue, String>
        val registry =
            mutatorRegistry<MutationsTestKey, MutableValue> {
                rename =
                    mutator(
                        id = "rename",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, next -> MutationPresence.Present(MutableValue(next)) }
            }
        val codec = RecordingMutableValueCodec()
        val server = MutatingThenFailingServer()
        val sharedBase = MutableValue("base")
        val engine =
            MutationEngine(
                registry = registry,
                server = server,
                valueCodecVersion = 5,
                valueCodec = codec,
                baseReader = { sharedBase },
            )
        engine.bind(MutableValueNoopHandle)
        val key = MutationsTestKey("semantic-retry")
        engine.mutate(key, rename, "mine-value")

        // First attempt: the server mutates the delivered carrier's values, then fails.
        engine.drain(key)
        // Retry of the same semantic generation.
        engine.drain(key)

        assertEquals(2, server.pushes.size)
        val first = server.pushes[0]
        val second = server.pushes[1]
        // The server's mutation leaked nowhere: not into the engine's shared base instance and
        // not into the retry's reconstruction.
        assertEquals("base", sharedBase.text)
        assertEquals(
            "base",
            assertIs<MutationPresence.Present<MutableValue>>(second.base).value.text,
        )
        assertEquals(
            "mine-value",
            assertIs<MutationPresence.Present<MutableValue>>(second.mine).value.text,
        )
        // The delivered instances are copies, never the engine's stored/projected objects.
        assertNotSame(
            sharedBase,
            assertIs<MutationPresence.Present<MutableValue>>(first.base).value,
        )
        // Same semantic generation across the retry: identity, client identity/sequence,
        // generation, deterministic idempotency key, and codec version all reconstruct equal.
        assertEquals(first.identity.namespace, second.identity.namespace)
        assertEquals(first.identity.canonicalId, second.identity.canonicalId)
        assertEquals(first.clientId, second.clientId)
        assertEquals(first.clientSequence, second.clientSequence)
        assertEquals(first.mutationId, second.mutationId)
        assertEquals(first.generation, second.generation)
        assertEquals(first.idempotencyKey, second.idempotencyKey)
        assertEquals("client-0:1:g1", second.idempotencyKey)
        assertEquals(5, second.valueCodecVersion)
        // The engine's copy boundary hands the codec the persisted version.
        assertEquals(5, codec.lastDecodeVersion)
    }

    // R1-08 adjunct (T4.5): the acknowledged authoritative value is rebuilt through the codec's
    // copy boundaries before adoption and echo-forward, so a server retaining its acknowledged
    // object cannot mutate adopted state or a later push's base.
    @Test
    fun ackAuthoritative_isDefensivelyCopiedBeforeAdoptionAndEchoForward() = runTest {
        lateinit var append: MutatorRef<MutationsTestKey, MutableValue, String>
        val registry =
            mutatorRegistry<MutationsTestKey, MutableValue> {
                append =
                    mutator(
                        id = "append",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { base, suffix ->
                        MutationPresence.Present(
                            MutableValue(
                                ((base as? MutationPresence.Present)?.value?.text).orEmpty() + suffix,
                            ),
                        )
                    }
            }
        val server = RetainingAckServer()
        val applied = mutableListOf<MutableValue>()
        val engine =
            MutationEngine(
                registry = registry,
                server = server,
                valueCodecVersion = 5,
                valueCodec = RecordingMutableValueCodec(),
                baseReader = { null },
            )
        engine.bind(
            object : StoreWriteHandle<MutationsTestKey, MutableValue> {
                override suspend fun apply(
                    key: MutationsTestKey,
                    value: MutableValue,
                ) {
                    applied += value
                }

                override suspend fun markStale(key: MutationsTestKey) = Unit

                override suspend fun confirmFresh(
                    key: MutationsTestKey,
                    etag: String?,
                ) = Unit
            },
        )
        val key = MutationsTestKey("retained-ack")
        engine.mutate(key, append, "+a")
        engine.mutate(key, append, "+b")

        engine.drain(key)

        // The second push's base echoed the first acknowledgement through the copy boundary.
        assertEquals(2, server.pushes.size)
        assertEquals(
            "auth-1",
            assertIs<MutationPresence.Present<MutableValue>>(server.pushes[1].base).value.text,
        )

        // The server now corrupts the authoritative object it retained; nothing it corrupted is
        // the object the engine adopted or echoed.
        server.retainedAcks.forEach { retained -> retained.text = "corrupted-by-server" }
        assertEquals(listOf("auth-1", "auth-2"), applied.map(MutableValue::text))
        assertEquals(
            "auth-1",
            assertIs<MutationPresence.Present<MutableValue>>(server.pushes[1].base).value.text,
        )
    }

    // R1-09: the sealed variants make a canonical target on confirmed absence unrepresentable.
    @Test
    fun absentAckHasNoCanonicalKeyAndPresentAckMayRekey() = runTest {
        val rekeyed =
            MutationPresentAck(
                authoritative = "value",
                etag = "etag-1",
                canonicalKey = MutationsTestKey("canonical"),
            )
        assertEquals("canonical", rekeyed.canonicalKey?.canonicalId())

        val unchanged =
            MutationPresentAck<MutationsTestKey, String>(
                authoritative = "value",
                etag = null,
                canonicalKey = null,
            )
        assertNull(unchanged.canonicalKey)

        // Compile-level: MutationAbsentAck's only constructable state is the etag. Exhaustive
        // dispatch needs no else branch.
        val absent: MutationAck<MutationsTestKey, String> =
            MutationAbsentAck<MutationsTestKey, String>(etag = "etag-2")
        when (absent) {
            is MutationPresentAck -> fail("Absent acknowledgement matched the present variant.")
            is MutationAbsentAck -> assertEquals("etag-2", absent.etag)
        }
    }

    // R1-10: library-side monotonic validation keeps the consumer-built carrier plain.
    @Test
    fun retirementAckRejectsRegressionAndPrefixAboveRequest() = runTest {
        val request = MutationRetirement(clientId = "client-1", retiredThroughSequence = 10L)

        // A confirmation above the requested prefix is a protocol violation.
        assertFailsWith<IllegalArgumentException> {
            validateRetirementAck(
                request = request,
                ack = MutationRetirementAck(confirmedThroughSequence = 12L),
                previousConfirmedThroughSequence = 5L,
            )
        }

        // A confirmation regressing below the persisted prefix is a protocol violation.
        assertFailsWith<IllegalArgumentException> {
            validateRetirementAck(
                request = request,
                ack = MutationRetirementAck(confirmedThroughSequence = 3L),
                previousConfirmedThroughSequence = 5L,
            )
        }

        // Monotonic advancement and idempotent re-confirmation are both legal.
        assertEquals(
            7L,
            validateRetirementAck(
                request = request,
                ack = MutationRetirementAck(confirmedThroughSequence = 7L),
                previousConfirmedThroughSequence = 5L,
            ),
        )
        assertEquals(
            5L,
            validateRetirementAck(
                request = request,
                ack = MutationRetirementAck(confirmedThroughSequence = 5L),
                previousConfirmedThroughSequence = 5L,
            ),
        )
    }

    // R1-19 (021 slice): the capture candidate carries immutable captured metadata and nothing
    // else — no selected baseMeta and no transport door. Any reference to `candidate.baseMeta`
    // or a push/server member does not compile.
    @Test
    fun preconditionCandidateHasCapturedMetaButNoSelectedBaseMetaOrTransportDoor() = runTest {
        val capturedMeta = TestMeta(writtenAtEpochMillis = 42L, etag = "captured")
        val candidate =
            MutationPreconditionCandidate(
                identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1"),
                key = MutationsTestKey("entity-1"),
                mutationId = "mutation-9",
                generation = 2,
                base = MutationPresence.Present("base"),
                mine = MutationPresence.Present("mine"),
                capturedMeta = capturedMeta,
            )

        assertEquals("mutations", candidate.identity.namespace)
        assertEquals("entity-1", candidate.identity.canonicalId)
        assertEquals("entity-1", candidate.key.canonicalId())
        assertEquals("mutation-9", candidate.mutationId)
        assertEquals(2, candidate.generation)
        assertEquals("base", assertIs<MutationPresence.Present<String>>(candidate.base).value)
        assertEquals("mine", assertIs<MutationPresence.Present<String>>(candidate.mine).value)
        assertSame<StoreMeta>(capturedMeta, assertIs<StoreMeta>(candidate.capturedMeta))

        // Captured metadata may be absent without weakening the existence precondition.
        val withoutMeta =
            MutationPreconditionCandidate(
                identity = MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1"),
                key = MutationsTestKey("entity-1"),
                mutationId = "mutation-9",
                generation = 2,
                base = MutationPresence.Absent,
                mine = MutationPresence.Present("mine"),
                capturedMeta = null,
            )
        assertNull(withoutMeta.capturedMeta)
    }

    // R1-18 adjunct (carrier-level; the ordered-capture behavioral tests are T4.5's).
    @Test
    fun absentBaseIsExistencePreconditionNotUnconditionalWrite() = runTest {
        val push =
            libraryBuiltPush(
                base = MutationPresence.Absent,
                mine = MutationPresence.Present("mine"),
                baseMeta = null,
            )

        // Absent base plus null metadata is still a precondition: apply only if still absent.
        assertSame(MutationPresence.Absent, push.base)
        assertNull(push.baseMeta)
    }

    // T4.1 bullet: stable public enums expose the exact ruled value sets.
    @Test
    fun stablePublicEnums_exposeExactRuledValueSets() = runTest {
        assertEquals(
            listOf("PRESENT", "ABSENT"),
            MutationPresenceState.entries.map(MutationPresenceState::name),
        )
        assertEquals(
            listOf("PENDING", "INFLIGHT", "REFRESHING", "ADOPTING", "APPLYING_EFFECTS"),
            MutationPendingState.entries.map(MutationPendingState::name),
        )
        assertEquals(
            listOf(
                "IDENTITY", "CODEC", "PROJECTION", "PROTOCOL", "CONFLICT",
                "TRANSPORT", "ADOPTION", "EFFECT", "PERSISTENCE",
            ),
            MutationFailureKind.entries.map(MutationFailureKind::name),
        )
    }

    // T4.1 bullet: the normalized failure carries no raw cause and honours the byte budgets.
    @Test
    fun normalizedFailure_carriesNoRawCauseAndHonoursSanitizationContract() = runTest {
        val cause = IllegalStateException("boom at the transport boundary")
        val failure =
            sanitizedMutationFailure(
                kind = MutationFailureKind.TRANSPORT,
                detail = "transport\t-detail\nstack frame one",
                message = cause.stackTraceToString(),
                occurredAtEpochMillis = 7L,
            )

        assertEquals(MutationFailureKind.TRANSPORT, failure.kind)
        assertEquals(7L, failure.occurredAtEpochMillis)
        // Stack-trace lines and control characters never survive normalization.
        assertFalse(failure.detail.contains('\n'))
        assertFalse(failure.detail.any(Char::isISOControl))
        assertFalse(failure.message.contains('\n'))
        assertFalse(failure.message.any(Char::isISOControl))
        assertEquals("transport-detail", failure.detail)
        assertTrue(failure.detail.encodeToByteArray().size <= 128)
        assertTrue(failure.message.encodeToByteArray().size <= 1024)
    }
}

private class RepresentedKey(
    private val id: String,
    val revision: Int,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("mutations")

    override fun canonicalId(): String = id
}

private class TestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private class RecordingStringCodec : MutationCodec<String> {
    var lastEncodeResult: ByteArray? = null
    var lastDecodeVersion: Int? = null
    var lastDecodeBytes: ByteArray? = null

    override fun encode(value: String): ByteArray =
        value.encodeToByteArray().also { lastEncodeResult = it }

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String {
        lastDecodeVersion = version
        lastDecodeBytes = bytes
        return bytes.decodeToString()
    }
}

private class RecordingMutationServer : MutationServer<MutationsTestKey, String> {
    val pushes = mutableListOf<MutationPush<MutationsTestKey, String>>()
    val retirements = mutableListOf<MutationRetirement>()

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
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

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck {
        retirements += request
        return MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
    }
}

private fun libraryBuiltPush(
    identity: MutationKeyIdentity =
        MutationKeyIdentity(namespace = "mutations", canonicalId = "entity-1"),
    key: MutationsTestKey = MutationsTestKey("entity-1"),
    base: MutationPresence<String>,
    mine: MutationPresence<String>,
    valueCodecVersion: Int = 5,
    baseMeta: StoreMeta? = null,
): MutationPush<MutationsTestKey, String> =
    MutationPush(
        identity = identity,
        key = key,
        clientId = "client-1",
        clientSequence = 4L,
        retiredThroughSequence = 3L,
        mutationId = "mutation-9",
        generation = 2,
        idempotencyKey = "idempotency-2",
        valueCodecVersion = valueCodecVersion,
        base = base,
        mine = mine,
        baseMeta = baseMeta,
    )

private fun emptyStaleSet(): StaleSet<MutationsTestKey> =
    StaleSet(keys = emptySet(), namespaces = emptySet())

/** A deliberately mutable value type: the engine's copy boundaries are what protect it. */
private class MutableValue(
    var text: String,
)

private class RecordingMutableValueCodec : MutationCodec<MutableValue> {
    var lastDecodeVersion: Int? = null

    override fun encode(value: MutableValue): ByteArray = value.text.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): MutableValue {
        lastDecodeVersion = version
        return MutableValue(bytes.decodeToString())
    }
}

private object MutableValueNoopHandle : StoreWriteHandle<MutationsTestKey, MutableValue> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: MutableValue,
    ) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

/** Mutates the first delivered carrier's values, then fails every push as transport. */
private class MutatingThenFailingServer : MutationServer<MutationsTestKey, MutableValue> {
    val pushes = mutableListOf<MutationPush<MutationsTestKey, MutableValue>>()

    override suspend fun push(
        request: MutationPush<MutationsTestKey, MutableValue>,
    ): MutationAck<MutationsTestKey, MutableValue> {
        pushes += request
        if (pushes.size == 1) {
            (request.base as? MutationPresence.Present)?.value?.text = "mutated-by-server"
            (request.mine as? MutationPresence.Present)?.value?.text = "mutated-by-server"
        }
        throw IllegalStateException("transport failed")
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

/** Acknowledges each push with an authoritative object it retains for later corruption. */
private class RetainingAckServer : MutationServer<MutationsTestKey, MutableValue> {
    val pushes = mutableListOf<MutationPush<MutationsTestKey, MutableValue>>()
    val retainedAcks = mutableListOf<MutableValue>()

    override suspend fun push(
        request: MutationPush<MutationsTestKey, MutableValue>,
    ): MutationAck<MutationsTestKey, MutableValue> {
        pushes += request
        val authoritative = MutableValue("auth-${pushes.size}")
        retainedAcks += authoritative
        return MutationPresentAck(
            authoritative = authoritative,
            etag = "etag-${pushes.size}",
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
