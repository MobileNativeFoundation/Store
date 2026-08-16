@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationAckOrchestrationTest {
    @Test
    fun postAckResolverFailure_retainsAckOrEffectPhaseAndNeverRepushes() = runTest {
        val failures =
            PostAckHeldPhase.entries.flatMap { heldPhase ->
                assertPostAckResolverFailure(heldPhase)
            }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    @Test
    fun unknownAckValueCodecVersion_staysAckedAndRetriesWithoutRepush() = runTest {
        val backing = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(backing)
        val mutations = AckMutations()
        val backend = retainingAckBackend()
        val key = MutationsTestKey("unknown-ack-codec")
        val initialCodec = SelectiveStringCodec(mutableSetOf(99))
        storage.armFailTransaction(JournalFailPointBoundary.ADOPTION_ADVANCE)
        val initial =
            openAckEngine(
                storage = storage,
                mutations = mutations,
                backend = backend,
                handle = RecordingAckWriteHandle(),
                valueCodecVersion = 99,
                valueCodec = initialCodec,
            )
        val mutationId = initial.mutate(key, mutations.set, "authoritative")

        assertNotNull(captureFailure { initial.drain(key) })
        val held = storage.ackState(mutationId)
        assertEquals(StoredPhase.ACKED, held.execution.phase)
        assertEquals(99, held.acks.single().valueCodecVersion)
        val pushCount = backend.receivedPushes.size

        val reopenedCodec = SelectiveStringCodec(mutableSetOf(1))
        val reopened =
            openAckEngine(
                storage = storage,
                mutations = mutations,
                backend = backend,
                handle = RecordingAckWriteHandle(),
                valueCodecVersion = 1,
                valueCodec = reopenedCodec,
            )
        reopened.drain(key)

        val blocked = storage.ackState(mutationId)
        assertEquals(StoredPhase.ACKED, blocked.execution.phase)
        assertTrue(blocked.failures.any { it.kind == MutationFailureKind.CODEC })
        assertEquals(pushCount, backend.receivedPushes.size)
        assertTrue(reopened.deadLetters().isEmpty())

        reopenedCodec.supportedVersions += 99
        reopened.drain(key)

        assertEquals(StoredPhase.RETIRED, storage.ackState(mutationId).execution.phase)
        assertEquals(pushCount, backend.receivedPushes.size)
        assertTrue(reopened.deadLetters().isEmpty())
    }

    @Test
    fun ackValidationPrecedesAdoptionEffectsAndRetirement() = runTest {
        val backing = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(backing)
        val effectTarget = MutationsTestKey("invalid-ack-effect")
        val mutations = AckMutations(staleKey = effectTarget)
        val backend = FakeBackend()
        val source = MutationsTestKey("invalid-ack-source")
        val firstTarget = MutationsTestKey("invalid-ack-target-1")
        val secondTarget = MutationsTestKey("invalid-ack-target-2")
        backend.pushBehavior = { _, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "etag-${backend.receivedPushes.size}",
                canonicalKey =
                    if (backend.receivedPushes.size == 1) firstTarget else secondTarget,
            )
        }
        val handle = RecordingAckWriteHandle()
        val engine = openAckEngine(storage, mutations, backend, handle)
        val mutationId = engine.mutate(source, mutations.set, "invalid-ack")
        val revisionBefore = engine.aliasRevision(source.identity()).value
        storage.armFailTransaction(JournalFailPointBoundary.ACK_RECEIPT)

        assertNotNull(captureFailure { engine.drain(source) })
        val replayable = storage.ackState(mutationId)
        assertEquals(StoredPhase.INFLIGHT, replayable.execution.phase)
        assertTrue(replayable.acks.isEmpty())
        assertEquals(1, backend.receivedPushes.size)

        engine.drain(source)

        val parked = storage.ackState(mutationId)
        val protocolFailures = parked.failures.filter { it.kind == MutationFailureKind.PROTOCOL }
        assertEquals(StoredPhase.PARKED, parked.execution.phase)
        assertEquals(1, parked.execution.attempt)
        assertNotNull(parked.execution.lastAttemptAt)
        assertEquals(protocolFailures.single().failureId, parked.execution.activeFailureId)
        assertNull(parked.execution.retiredAt)
        assertTrue(parked.acks.isEmpty())
        assertTrue(parked.aliases.isEmpty())
        assertTrue(parked.tombstones.isEmpty())
        assertEquals(1, parked.effects.size)
        assertEquals(MutationEffectDisposition.PENDING, parked.effects.single().disposition)
        assertTrue(handle.applied.isEmpty())
        assertTrue(handle.confirmed.isEmpty())
        assertTrue(handle.markedStale.isEmpty())
        assertEquals(0L, assertNotNull(parked.client).retiredThroughSequence)
        assertEquals(revisionBefore, engine.aliasRevision(source.identity()).value)
        val deadLetter = engine.deadLetters().single()
        assertEquals(mutationId, deadLetter.mutationId)
        assertEquals(MutationFailureKind.PROTOCOL, deadLetter.failure.kind)

        val pushCount = backend.receivedPushes.size
        engine.drain(source)
        assertEquals(pushCount, backend.receivedPushes.size)
    }

    @Test
    fun adoptionFailure_staysAckedAndRetriesWithoutRepush() = runTest {
        val errors = mutableListOf<String>()
        val storage = InMemoryMutationJournalStorage()
        val mutations = AckMutations()
        val backend = retainingAckBackend()
        val handle = RecordingAckWriteHandle(remainingApplyFailures = 1)
        val engine = openAckEngine(storage, mutations, backend, handle)
        val key = MutationsTestKey("adoption-retry")
        val mutationId = engine.mutate(key, mutations.set, "accepted")

        val exposed = captureFailure { engine.drain(key) }
        expect(errors, "keyed adoption failure is sanctioned") {
            val adoptionFailure = assertIs<IllegalStateException>(assertNotNull(exposed))
            assertEquals("injected adoption failure", adoptionFailure.message)
        }
        val held = storage.ackState(mutationId)
        expect(errors, "adoption failure retains ACKED") {
            assertEquals(StoredPhase.ACKED, held.execution.phase)
        }
        expect(errors, "adoption failure records evidence") {
            assertTrue(held.failures.any { it.kind == MutationFailureKind.ADOPTION })
        }
        val heldDeadLetters = engine.deadLetters()
        expect(errors, "adoption failure never retransmits or parks") {
            assertEquals(1, backend.receivedPushes.size)
            assertTrue(heldDeadLetters.isEmpty())
        }

        val cleanFailure = captureFailure { engine.drain(key) }
        expect(errors, "later adoption pass completes") { assertNull(cleanFailure) }
        val completed = storage.ackState(mutationId)
        val completedDeadLetters = engine.deadLetters()
        expect(errors, "later adoption retires") {
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            assertEquals(2, handle.applyAttempts)
            assertEquals(1, handle.applied.size)
            assertEquals(1, handle.confirmed.size)
            assertEquals(1, backend.receivedPushes.size)
            assertTrue(completedDeadLetters.isEmpty())
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun persistenceFailureAtEachPostAckBoundary_replaysFromLastDurablePhase() = runTest {
        val errors =
            listOf(
                JournalFailPointBoundary.ACK_RECEIPT,
                JournalFailPointBoundary.ADOPTION_ADVANCE,
                JournalFailPointBoundary.EFFECT_MARKER,
                JournalFailPointBoundary.FINALIZATION,
            ).flatMap { boundary ->
                persistenceReceiptForms(boundary).flatMap { receipt ->
                    assertPersistenceBoundary(boundary, receipt)
                }
            }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun ackAliasActivationRebasesQueuedSourceAndTargetSiblings() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = AckMutations()
        val backend = retainingAckBackend()
        val source = MutationsTestKey("queue-source")
        val target = MutationsTestKey("queue-target")
        backend.pushBehavior = { key, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "etag-${backend.receivedPushes.size}",
                canonicalKey = target.takeIf { key.canonicalId() == source.canonicalId() },
            )
        }
        val store =
            mutationStore(
                registry = mutations.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                journalStorage(storage)
            }
        try {
            val head = store.mutate(source, mutations.set, "head")
            val sourceSibling = store.mutate(source, mutations.append, "+source")
            val targetSibling = store.mutate(target, mutations.append, "+target")

            store.drain(source)

            assertEquals(
                listOf("queue-source", "queue-target", "queue-target"),
                backend.receivedPushes.map { it.identity.canonicalId },
            )
            assertEquals(listOf(1L, 2L, 3L), backend.receivedPushes.map { it.clientSequence })
            assertEquals(listOf("head", "head+source", "head+source+target"), backend.pushedValues)
            assertEquals(emptyList(), store.pending(source))
            assertEquals(emptyList(), store.pending(target))
            assertEquals(emptyList(), store.pendingWrites())
            for (mutationId in listOf(head, sourceSibling, targetSibling)) {
                assertEquals(StoredPhase.RETIRED, storage.ackState(mutationId).execution.phase)
            }
            val terminal = storage.ackState(head)
            assertEquals(MutationAliasState.ACTIVE, terminal.aliases.single().state)
            assertEquals(3L, assertNotNull(terminal.client).retiredThroughSequence)
        } finally {
            store.close()
        }
    }
}

private enum class PostAckHeldPhase(
    val failBoundary: JournalFailPointBoundary,
    val storedPhase: StoredPhase,
) {
    ACKED(JournalFailPointBoundary.ADOPTION_ADVANCE, StoredPhase.ACKED),
    EFFECTS_PENDING(JournalFailPointBoundary.FINALIZATION, StoredPhase.EFFECTS_PENDING),
}

private enum class PersistenceReceipt {
    PRESENT,
    PRESENT_ALIAS,
    ABSENT_TOMBSTONE,
}

private fun persistenceReceiptForms(
    boundary: JournalFailPointBoundary,
): List<PersistenceReceipt> =
    if (
        boundary == JournalFailPointBoundary.ACK_RECEIPT ||
        boundary == JournalFailPointBoundary.FINALIZATION
    ) {
        listOf(PersistenceReceipt.PRESENT_ALIAS, PersistenceReceipt.ABSENT_TOMBSTONE)
    } else {
        listOf(PersistenceReceipt.PRESENT)
    }

private suspend fun assertPostAckResolverFailure(heldPhase: PostAckHeldPhase): List<String> {
    val errors = mutableListOf<String>()
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val mutations = AckMutations()
    val backend = retainingAckBackend()
    val source = MutationsTestKey("resolver-${heldPhase.name.lowercase()}-source")
    val target = MutationsTestKey("resolver-${heldPhase.name.lowercase()}-target")
    backend.pushBehavior = { _, value ->
        MutationPresentAck(value, "resolver-etag", target)
    }
    val resolver = ScriptedAckResolver()
    val engine =
        openAckEngine(
            storage = storage,
            mutations = mutations,
            backend = backend,
            handle = RecordingAckWriteHandle(),
            keyResolver = resolver,
        )
    val mutationId = engine.mutate(source, mutations.set, "resolver-value")
    storage.armFailTransaction(heldPhase.failBoundary)

    val stagingFailure = captureFailure { engine.drain(source) }
    val staged = storage.ackState(mutationId)
    expect(errors, "staging leaves ${heldPhase.storedPhase}") {
        assertNotNull(stagingFailure)
        assertEquals(heldPhase.storedPhase, staged.execution.phase)
    }
    val pushCount = backend.receivedPushes.size
    engine.clearLiveKeyCache()
    resolver.failure = IllegalStateException("post-ack resolver unavailable")

    val exposed = captureFailure { engine.drain(source) }
    expect(errors, "resolver failure is sanctioned") {
        val storeFailure = assertIs<StoreException>(assertNotNull(exposed))
        assertIs<StoreError.Conversion>(storeFailure.error)
    }
    val failed = storage.ackState(mutationId)
    val failedDeadLetters = engine.deadLetters()
    expect(errors, "resolver failure retains phase and evidence") {
        assertEquals(heldPhase.storedPhase, failed.execution.phase)
        assertTrue(failed.failures.any { it.kind == MutationFailureKind.IDENTITY })
        assertEquals(pushCount, backend.receivedPushes.size)
        assertTrue(failedDeadLetters.isEmpty())
    }

    resolver.failure = null
    val cleanFailure = captureFailure { engine.drain(source) }
    val completed = storage.ackState(mutationId)
    val completedDeadLetters = engine.deadLetters()
    expect(errors, "later resolver success completes without a push") {
        assertNull(cleanFailure)
        assertEquals(StoredPhase.RETIRED, completed.execution.phase)
        assertEquals(pushCount, backend.receivedPushes.size)
        assertTrue(completedDeadLetters.isEmpty())
    }
    return errors.map { "${heldPhase.name}: $it" }
}

private suspend fun assertPersistenceBoundary(
    boundary: JournalFailPointBoundary,
    receipt: PersistenceReceipt,
): List<String> {
    val errors = mutableListOf<String>()
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val source = MutationsTestKey("${boundary.name.lowercase()}-source")
    val target = MutationsTestKey("${boundary.name.lowercase()}-target")
    val effectTarget = MutationsTestKey("${boundary.name.lowercase()}-effect")
    val mutations =
        AckMutations(
            staleKey = effectTarget.takeIf { boundary == JournalFailPointBoundary.EFFECT_MARKER },
        )
    val backend =
        retainingAckBackend().apply {
            dedupingPushBehavior = true
            pushBehavior = { _, value ->
                MutationPresentAck(
                    authoritative = value,
                    etag = "boundary-etag",
                    canonicalKey = target.takeIf { receipt == PersistenceReceipt.PRESENT_ALIAS },
                )
            }
            absentPushBehavior = { _ -> MutationAbsentAck(etag = "boundary-absent-etag") }
        }
    val firstHandle = RecordingAckWriteHandle()
    val first = openAckEngine(storage, mutations, backend, firstHandle)
    val mutationId =
        when (receipt) {
            PersistenceReceipt.PRESENT,
            PersistenceReceipt.PRESENT_ALIAS,
            -> first.mutate(source, mutations.set, "boundary-value")

            PersistenceReceipt.ABSENT_TOMBSTONE -> first.mutate(source, mutations.deleteRef, Unit)
        }
    val revisionBefore = first.aliasRevision(source.identity()).value
    val changesBefore = first.changes.replayCache.map { it.fingerprint() }
    storage.armFailTransaction(boundary)

    val exposed = captureFailure { first.drain(source) }
    expect(errors, "$boundary/$receipt failure is surfaced") {
        assertIs<FailPointTransactionException>(assertNotNull(exposed))
    }
    val held = storage.ackState(mutationId)
    val expectedHeldPhase =
        when (boundary) {
            JournalFailPointBoundary.ACK_RECEIPT -> StoredPhase.INFLIGHT
            JournalFailPointBoundary.ADOPTION_ADVANCE -> StoredPhase.ACKED
            JournalFailPointBoundary.EFFECT_MARKER,
            JournalFailPointBoundary.FINALIZATION,
            -> StoredPhase.EFFECTS_PENDING
        }
    expect(errors, "$boundary/$receipt retains the last durable phase") {
        assertEquals(expectedHeldPhase, held.execution.phase)
        assertEquals(0L, assertNotNull(held.client).retiredThroughSequence)
        assertEquals(listOf(boundary), storage.triggeredBoundaries)
        assertTrue(!storage.hasArmedFailPoint)
    }
    expect(errors, "$boundary/$receipt publishes no premature accepted-state revision") {
        assertEquals(revisionBefore, first.aliasRevision(source.identity()).value)
        assertEquals(changesBefore, first.changes.replayCache.map { it.fingerprint() })
    }
    when (boundary) {
        JournalFailPointBoundary.ACK_RECEIPT ->
            expect(errors, "$boundary/$receipt rolls back the complete receipt") {
                assertTrue(held.acks.isEmpty())
                assertTrue(held.aliases.isEmpty())
                assertTrue(held.tombstones.isEmpty())
                assertEquals(0, firstHandle.applyAttempts)
                assertTrue(firstHandle.cleared.isEmpty())
                assertTrue(firstHandle.confirmed.isEmpty())
                assertTrue(firstHandle.markedStale.isEmpty())
            }

        JournalFailPointBoundary.ADOPTION_ADVANCE ->
            expect(errors, "$boundary leaves ACKED after external adoption") {
                assertEquals(1, held.acks.size)
                assertEquals(1, firstHandle.applyAttempts)
                assertEquals(1, firstHandle.confirmed.size)
            }

        JournalFailPointBoundary.EFFECT_MARKER ->
            expect(errors, "$boundary leaves the effect pending after target success") {
                assertEquals(
                    MutationEffectDisposition.PENDING,
                    assertNotNull(held.effects.singleOrNull()).disposition,
                )
                assertEquals(listOf(effectTarget.identity()), firstHandle.markedStale)
            }

        JournalFailPointBoundary.FINALIZATION ->
            expect(errors, "$boundary/$receipt retains pending routing") {
                when (receipt) {
                    PersistenceReceipt.PRESENT_ALIAS -> {
                        assertEquals(
                            MutationAliasState.PENDING,
                            assertNotNull(held.aliases.singleOrNull()).state,
                        )
                        assertTrue(held.tombstones.isEmpty())
                    }

                    PersistenceReceipt.ABSENT_TOMBSTONE -> {
                        assertTrue(held.aliases.isEmpty())
                        assertEquals(
                            MutationTombstoneState.PENDING,
                            assertNotNull(held.tombstones.singleOrNull()).state,
                        )
                    }

                    PersistenceReceipt.PRESENT ->
                        throw AssertionError("FINALIZATION requires an optional receipt subarm.")
                }
                assertEquals(source.identity(), first.terminalIdentityOf(source.identity()))
            }
    }

    val reopenedHandle = RecordingAckWriteHandle()
    val reopened = openAckEngine(storage, mutations, backend, reopenedHandle)
    val cleanFailure = captureFailure { reopened.drain(source) }
    expect(errors, "$boundary/$receipt clean restart completes") { assertNull(cleanFailure) }
    val completed = storage.ackState(mutationId)
    val completedDeadLetters = reopened.deadLetters()
    expect(errors, "$boundary/$receipt clean restart retires") {
        assertEquals(StoredPhase.RETIRED, completed.execution.phase)
        assertEquals(1L, assertNotNull(completed.client).retiredThroughSequence)
        assertTrue(completedDeadLetters.isEmpty())
    }
    when (boundary) {
        JournalFailPointBoundary.ACK_RECEIPT ->
            expect(errors, "$boundary/$receipt replays one deduped request and activates receipt") {
                assertEquals(2, backend.receivedIdempotencyKeys.size)
                assertEquals(
                    backend.receivedIdempotencyKeys.first(),
                    backend.receivedIdempotencyKeys.last(),
                )
                assertEquals(1, backend.effectivePushApplications.size)
                when (receipt) {
                    PersistenceReceipt.PRESENT_ALIAS -> {
                        assertEquals(
                            MutationAliasState.ACTIVE,
                            assertNotNull(completed.aliases.singleOrNull()).state,
                        )
                        assertTrue(completed.tombstones.isEmpty())
                        assertEquals(target.identity(), reopened.terminalIdentityOf(source.identity()))
                        assertEquals(1, reopenedHandle.applyAttempts)
                        assertTrue(reopenedHandle.cleared.isEmpty())
                    }

                    PersistenceReceipt.ABSENT_TOMBSTONE -> {
                        assertTrue(completed.aliases.isEmpty())
                        assertEquals(
                            MutationTombstoneState.ACTIVE,
                            assertNotNull(completed.tombstones.singleOrNull()).state,
                        )
                        assertEquals(source.identity(), reopened.terminalIdentityOf(source.identity()))
                        assertEquals(0, reopenedHandle.applyAttempts)
                        assertEquals(listOf(source.identity()), reopenedHandle.cleared)
                    }

                    PersistenceReceipt.PRESENT ->
                        throw AssertionError("ACK_RECEIPT requires an optional receipt subarm.")
                }
            }

        JournalFailPointBoundary.ADOPTION_ADVANCE ->
            expect(errors, "$boundary re-adopts without a push") {
                assertEquals(2, firstHandle.applyAttempts + reopenedHandle.applyAttempts)
                assertEquals(1, backend.receivedPushes.size)
            }

        JournalFailPointBoundary.EFFECT_MARKER ->
            expect(errors, "$boundary repeats the target before applying its marker") {
                assertTrue(firstHandle.markedStale.size + reopenedHandle.markedStale.size >= 2)
                assertEquals(
                    MutationEffectDisposition.APPLIED,
                    assertNotNull(completed.effects.singleOrNull()).disposition,
                )
                assertEquals(1, backend.receivedPushes.size)
            }

        JournalFailPointBoundary.FINALIZATION ->
            expect(errors, "$boundary/$receipt activates routing only on the clean pass") {
                when (receipt) {
                    PersistenceReceipt.PRESENT_ALIAS -> {
                        assertEquals(
                            MutationAliasState.ACTIVE,
                            assertNotNull(completed.aliases.singleOrNull()).state,
                        )
                        assertTrue(completed.tombstones.isEmpty())
                        assertEquals(target.identity(), reopened.terminalIdentityOf(source.identity()))
                    }

                    PersistenceReceipt.ABSENT_TOMBSTONE -> {
                        assertTrue(completed.aliases.isEmpty())
                        assertEquals(
                            MutationTombstoneState.ACTIVE,
                            assertNotNull(completed.tombstones.singleOrNull()).state,
                        )
                        assertEquals(source.identity(), reopened.terminalIdentityOf(source.identity()))
                    }

                    PersistenceReceipt.PRESENT ->
                        throw AssertionError("FINALIZATION requires an optional receipt subarm.")
                }
                assertEquals(1, backend.receivedPushes.size)
            }
    }
    return errors.map { "$boundary/$receipt: $it" }
}

private class AckMutations(
    staleKey: MutationsTestKey? = null,
) {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>

    private val setStales: (MutationsTestKey, String) -> StaleSet<MutationsTestKey> =
        if (staleKey == null) noStales() else typedStales<String>(keys = setOf(staleKey))

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                mutator(
                    id = "ack-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = setStales,
                ) { _, value -> MutationPresence.Present(value) }
            append =
                mutator(
                    id = "ack-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
            deleteRef = delete(id = "ack-delete", stales = noStales())
        }
}

private class ScriptedAckResolver : MutationKeyResolver<MutationsTestKey> {
    var failure: Throwable? = null

    override suspend fun resolve(identity: MutationKeyIdentity): MutationsTestKey? {
        failure?.let { throw it }
        return MutationsTestKeyResolver.resolve(identity)
    }
}

private class SelectiveStringCodec(
    val supportedVersions: MutableSet<Int>,
) : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String {
        require(version in supportedVersions) { "Unsupported fixture value codec version $version." }
        return bytes.decodeToString()
    }
}

private class RecordingAckWriteHandle(
    var remainingApplyFailures: Int = 0,
) : StoreWriteHandle<MutationsTestKey, String> {
    var applyAttempts: Int = 0
        private set
    val applied = mutableListOf<Pair<KeyIdentity, String>>()
    val confirmed = mutableListOf<Pair<KeyIdentity, String?>>()
    val markedStale = mutableListOf<KeyIdentity>()
    val cleared = mutableListOf<KeyIdentity>()

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        applyAttempts += 1
        if (remainingApplyFailures > 0) {
            remainingApplyFailures -= 1
            throw IllegalStateException("injected adoption failure")
        }
        applied += key.identity() to value
    }

    override suspend fun markStale(key: MutationsTestKey) {
        markedStale += key.identity()
    }

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) {
        confirmed += key.identity() to etag
    }

    fun recordClear(key: MutationsTestKey) {
        cleared += key.identity()
    }
}

private fun retainingAckBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun openAckEngine(
    storage: MutationJournalStorage,
    mutations: AckMutations,
    backend: FakeBackend,
    handle: RecordingAckWriteHandle,
    keyResolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    valueCodecVersion: Int = 1,
    valueCodec: MutationCodec<String> = FixtureStringArgsCodec,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = ACK_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = keyResolver,
        valueCodecVersion = valueCodecVersion,
        valueCodec = valueCodec,
        baseReader = { "base" },
        absentAdoption = { key -> handle.recordClear(key) },
        clientId = ACK_CLIENT_ID,
    ).also { it.bind(handle) }
}

private data class AckDurableState(
    val client: MutationClientRecord?,
    val intent: MutationIntentRecord,
    val execution: MutationExecutionRecord,
    val acks: List<MutationAckRecord>,
    val failures: List<MutationFailureRecord>,
    val effects: List<MutationEffectRecord>,
    val aliases: List<MutationKeyAliasRecord>,
    val tombstones: List<MutationKeyTombstoneRecord>,
)

private suspend fun MutationJournalStorage.ackState(mutationId: String): AckDurableState =
    transaction { transaction ->
        val intent = transaction.intents(ACK_CLIENT_ID).single { it.mutationId == mutationId }
        val sequence = intent.clientSequence
        AckDurableState(
            client = transaction.client(ACK_CLIENT_ID),
            intent = intent,
            execution = transaction.executions(ACK_CLIENT_ID).single { it.clientSequence == sequence },
            acks = transaction.acks(ACK_CLIENT_ID).filter { it.clientSequence == sequence },
            failures = transaction.failures(ACK_CLIENT_ID).filter { it.clientSequence == sequence },
            effects = transaction.effects(ACK_CLIENT_ID).filter { it.clientSequence == sequence },
            aliases =
                transaction.aliases().filter {
                    it.createdByClientId == ACK_CLIENT_ID && it.createdBySequence == sequence
                },
            tombstones =
                transaction.tombstones().filter {
                    it.createdByClientId == ACK_CLIENT_ID && it.createdBySequence == sequence
                },
        )
    }

private suspend fun captureFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

private fun expect(
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

private fun StoreKey.fingerprint(): String =
    "${namespace.value}:${canonicalId()}"

private const val ACK_CLIENT_ID = "client-0"
private val TEST_TIMEOUT = 25.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) { testBody() }
