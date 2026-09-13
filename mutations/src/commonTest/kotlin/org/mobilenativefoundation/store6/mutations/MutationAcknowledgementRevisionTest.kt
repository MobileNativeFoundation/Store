@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FreshnessEvidence
import org.mobilenativefoundation.store6.core.seam.SourceAdoption
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationAcknowledgementRevisionTest {
    @Test
    fun keyInvalidationAfterPush_survivesAcknowledgement() = runTest(timeout = 25.seconds) {
        assertLaterInvalidation { store, key -> store.invalidate(key) }
    }

    @Test
    fun namespaceInvalidationAfterPush_survivesAcknowledgement() = runTest(timeout = 25.seconds) {
        assertLaterInvalidation { store, key -> store.invalidateNamespace(StoreNamespace(key.namespace.value)) }
    }

    @Test
    fun globalInvalidationAfterPush_survivesAcknowledgement() = runTest(timeout = 25.seconds) {
        assertLaterInvalidation { store, _ -> store.invalidateAll() }
    }

    @Test
    fun adoptedEcho_projectsQueuedSuffixWithoutRepeatingAcknowledgedAppend() = runTest(timeout = 25.seconds) {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val key = MutationsTestKey("ack-prefix")
        rows.write(key, "base")
        val projectedEcho = CompletableDeferred<String>()
        val mutations = RevisionAppend { base, projected ->
            if (base.startsWith("confirmed:")) projectedEcho.complete(projected)
        }
        val backend = RevisionServer()
        val actualBookkeeper = FakeBookkeeper()
        actualBookkeeper.recordSuccess(key, TestStoreMeta(1L, "seed"))
        val confirmationEntered = CompletableDeferred<Unit>()
        val releaseConfirmation = CompletableDeferred<Unit>()
        val bookkeeper = object : Bookkeeper by actualBookkeeper {
            override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) {
                if (meta.etag == "ack-1") {
                    confirmationEntered.complete(Unit)
                    releaseConfirmation.await()
                }
                actualBookkeeper.recordSuccess(key, meta)
            }
        }
        val store = revisionStore(rows, mutations, backend, bookkeeper)
        try {
            store.stream(key, Freshness.LocalOnly).test(timeout = 30.seconds) {
                try {
                    assertEquals("base", awaitData().value)
                    store.mutate(key, mutations.append, "+A")
                    assertEquals("base+A", awaitData().value)
                    val drain = async { store.drain(key) }
                    backend.firstPushEntered.await()
                    store.mutate(key, mutations.append, "+B")
                    assertEquals("base+A+B", awaitData().value)
                    backend.releaseFirstPush.complete(Unit)
                    confirmationEntered.await()

                    assertEquals("confirmed:base+A+B", projectedEcho.await())
                    assertEquals(2, store.pending(key).size)
                    releaseConfirmation.complete(Unit)
                    drain.await()
                    cancelAndIgnoreRemainingEvents()
                } finally {
                    backend.releaseFirstPush.complete(Unit)
                    releaseConfirmation.complete(Unit)
                }
            }
        } finally {
            backend.releaseFirstPush.complete(Unit)
            releaseConfirmation.complete(Unit)
            store.close()
        }
    }

    @Test
    fun reopenedAcknowledgementBeforeSource_keepsFrozenPrefixAndSuffix() = runTest(timeout = 25.seconds) {
        assertRestartProjection(JournalFailPointBoundary.ACK_RECEIPT, afterCommit = true)
    }

    @Test
    fun reopenedAcknowledgementAfterSource_keepsFrozenPrefixAndSuffix() = runTest(timeout = 25.seconds) {
        assertRestartProjection(JournalFailPointBoundary.ADOPTION_ADVANCE, afterCommit = false)
    }

    @Test
    fun reopenedEffectsPending_keepsFrozenPrefixAndSuffix() = runTest(timeout = 25.seconds) {
        assertRestartProjection(JournalFailPointBoundary.ADOPTION_ADVANCE, afterCommit = true)
    }

    @Test
    fun transportReplay_reusesOriginalFreshnessObservation() = runTest(timeout = 25.seconds) {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val key = MutationsTestKey("retry-freshness")
        rows.write(key, "base")
        val bookkeeper = FakeBookkeeper()
        bookkeeper.recordSuccess(key, TestStoreMeta(1L, "seed"))
        val backend = RevisionServer(firstFailure = IllegalStateException("response lost"))
        val mutations = RevisionAppend()
        val store = revisionStore(rows, mutations, backend, bookkeeper)
        try {
            store.mutate(key, mutations.append, "+A")
            backend.releaseFirstPush.complete(Unit)
            store.drain(key)
            store.invalidate(key)
            store.drain(key)

            assertEquals(2, backend.requests.size)
            assertEquals(backend.requests[0].idempotencyKey, backend.requests[1].idempotencyKey)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertTrue(store.pending(key).isEmpty())
        } finally {
            backend.releaseFirstPush.complete(Unit)
            store.close()
        }
    }

    @Test
    fun canonicalAcknowledgement_doesNotCaptureFreshnessAtItsLateTarget() = runTest(timeout = 25.seconds) {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val source = MutationsTestKey("provisional")
        val target = MutationsTestKey("canonical")
        rows.write(source, "base")
        rows.write(target, "previous target")
        val bookkeeper = FakeBookkeeper()
        bookkeeper.recordSuccess(source, TestStoreMeta(1L, "source"))
        bookkeeper.recordSuccess(target, TestStoreMeta(1L, "target"))
        val backend = RevisionServer(canonicalTarget = target)
        val mutations = RevisionAppend()
        val store = revisionStore(rows, mutations, backend, bookkeeper)
        try {
            store.mutate(source, mutations.append, "+A")
            val drain = async { store.drain(source) }
            backend.firstPushEntered.await()
            store.invalidate(target)
            backend.releaseFirstPush.complete(Unit)
            drain.await()

            assertEquals("confirmed:base+A", store.get(source, Freshness.LocalOnly))
            assertTrue(assertNotNull(bookkeeper.status(target)).durablyStale)
        } finally {
            backend.releaseFirstPush.complete(Unit)
            store.close()
        }
    }

    @Test
    fun unprovenEqualObservation_usesCapturedPreHeadBase() = runTest(timeout = 25.seconds) {
        assertUnprovenObservation(evict = false)
    }

    @Test
    fun evictedAcknowledgementObservation_usesCapturedPreHeadBase() = runTest(timeout = 25.seconds) {
        assertUnprovenObservation(evict = true)
    }

    @Test
    fun mergedAcknowledgement_usesSavedRetryProjectionForUnprovenObservation() = runTest(timeout = 25.seconds) {
        assertUnprovenObservation(evict = false, merged = true)
    }

    private suspend fun assertUnprovenObservation(
        evict: Boolean,
        merged: Boolean = false,
    ) = kotlinx.coroutines.coroutineScope {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val key = MutationsTestKey("unproven")
        rows.write(key, "base")
        val bookkeeper = FakeBookkeeper()
        bookkeeper.recordSuccess(key, TestStoreMeta(1L, "seed"))
        val backend = RevisionServer(
            firstFailure = if (merged) {
                StoreResults.exception(
                    StoreResults.conflict(null, "retry with a merged value"),
                    IllegalStateException("server conflict"),
                )
            } else {
                null
            },
            holdSecondPush = merged,
        )
        val mutations = RevisionAppend()
        lateinit var store: Store<MutationsTestKey, String>
        val engine = MutationEngine(
            registry = mutations.registry,
            server = backend,
            journal = StorageBackedMutationJournal(
                storage = InMemoryMutationJournalStorage(),
                registrations = mutations.registry.registrations,
                hydrateOnFirstUse = true,
            ),
            sourceOfTruth = rows,
            bookkeeper = bookkeeper,
            keyResolver = MutationsTestKeyResolver,
            valueCodecVersion = 1,
            valueCodec = FixtureStringArgsCodec,
            baseReader = { store.get(it, Freshness.LocalOnly) },
            conflicts = if (merged) {
                MutationConflictRegistration(
                    precondition = null,
                    merge = { _, _, _ -> MutationConflictResolution.Retry(MutationPresence.Present("merged")) },
                )
            } else {
                null
            },
        )
        store = org.mobilenativefoundation.store6.core.store<MutationsTestKey, String> {
            persistence(rows)
            bookkeeper(bookkeeper)
            overlay(engine.overlay)
            maxIdleKeys(if (evict) 0 else 128)
            fetcher { error("The unproven-observation test must not fetch") }
        }
        val actual = requireNotNull(store.runtime()).writeHandle
        val adopted = CompletableDeferred<Unit>()
        val releaseAdoption = CompletableDeferred<Unit>()
        engine.bind(object : StoreWriteHandle<MutationsTestKey, String> by actual {
            override suspend fun apply(key: MutationsTestKey, value: String) {
                actual.apply(key, value)
                adopted.complete(Unit)
                releaseAdoption.await()
            }

            override suspend fun applyAcknowledgement(
                key: MutationsTestKey,
                value: String,
                etag: String?,
                freshnessEvidence: FreshnessEvidence?,
                adoption: SourceAdoption?,
            ) {
                actual.applyAcknowledgement(key, value, etag, freshnessEvidence, adoption)
                adopted.complete(Unit)
                releaseAdoption.await()
            }
        })
        try {
            engine.mutate(key, mutations.append, "+A")
            if (merged) {
                backend.releaseFirstPush.complete(Unit)
                engine.drain(key)
            }
            val drain = async { engine.drain(key) }
            if (merged) backend.secondPushEntered.await() else backend.firstPushEntered.await()
            engine.mutate(key, mutations.append, "+B")
            backend.releaseFirstPush.complete(Unit)
            backend.releaseSecondPush.complete(Unit)
            adopted.await()
            if (evict) {
                store.stream(key, Freshness.LocalOnly).test(timeout = 30.seconds) {
                    assertEquals("base+A+B", awaitData().value)
                    cancelAndIgnoreRemainingEvents()
                }
            } else {
                val unprovenRaw = rows.reader(key).first()
                val pendingHead = if (merged) "merged" else "base+A"
                assertEquals("confirmed:$pendingHead", unprovenRaw)
                assertEquals("$pendingHead+B", engine.overlay.apply(key, unprovenRaw, adoption = null))
                assertEquals("$pendingHead+B", engine.overlay.apply(key, unprovenRaw, adoption = SourceAdoption()))
            }
            releaseAdoption.complete(Unit)
            drain.await()
        } finally {
            backend.releaseFirstPush.complete(Unit)
            releaseAdoption.complete(Unit)
            backend.releaseSecondPush.complete(Unit)
            store.close()
        }
    }

    private suspend fun assertLaterInvalidation(
        invalidate: suspend (MutationStore<MutationsTestKey, String>, MutationsTestKey) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val key = MutationsTestKey("later-invalidation")
        rows.write(key, "base")
        val bookkeeper = FakeBookkeeper()
        bookkeeper.recordSuccess(key, TestStoreMeta(1L, "seed"))
        val backend = RevisionServer()
        val mutations = RevisionAppend()
        val store = revisionStore(rows, mutations, backend, bookkeeper)
        try {
            store.mutate(key, mutations.append, "+A")
            val drain = async { store.drain(key) }
            backend.firstPushEntered.await()
            invalidate(store, key)
            backend.releaseFirstPush.complete(Unit)
            drain.await()

            assertEquals("confirmed:base+A", store.get(key, Freshness.LocalOnly))
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals(1, backend.pushes)
        } finally {
            backend.releaseFirstPush.complete(Unit)
            store.close()
        }
    }

    private suspend fun assertRestartProjection(
        boundary: JournalFailPointBoundary,
        afterCommit: Boolean,
    ) = kotlinx.coroutines.coroutineScope {
        val rows = FakeSourceOfTruth<MutationsTestKey, String>()
        val key = MutationsTestKey("restart-ack")
        rows.write(key, "base")
        val backing = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(backing)
        val backend = RevisionServer()
        val mutations = RevisionAppend()
        val first = revisionStore(rows, mutations, backend, storage = storage)
        try {
            first.mutate(key, mutations.append, "+A")
            val drain = async { runCatching { first.drain(key) } }
            backend.firstPushEntered.await()
            first.mutate(key, mutations.append, "+B")
            if (afterCommit) storage.armKillAfterCommit(boundary) else storage.armKillBeforeCommit(boundary)
            backend.releaseFirstPush.complete(Unit)
            assertNotNull(drain.await().exceptionOrNull())
        } finally {
            backend.releaseFirstPush.complete(Unit)
            first.close()
        }

        val reopened = revisionStore(rows, mutations, backend, storage = storage)
        try {
            assertEquals(2, reopened.pending(key).size)
            reopened.stream(key, Freshness.LocalOnly).test(timeout = 30.seconds) {
                assertEquals("base+A+B", awaitData().value)
                cancelAndIgnoreRemainingEvents()
            }
            reopened.drain(key)
            assertTrue(reopened.pending(key).isEmpty())
            assertEquals(2, backend.pushes)
        } finally {
            reopened.close()
        }
    }
}

private class RevisionAppend(
    onSuffixProjection: (base: String, projected: String) -> Unit = { _, _ -> },
) {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    val registry = mutatorRegistry<MutationsTestKey, String> {
        append = upsert(
            id = "append",
            version = 1,
            codec = FixtureStringArgsCodec,
            stales = noStales(),
        ) { base, suffix ->
            val current = (base as? MutationPresence.Present)?.value.orEmpty()
            val projected = current + suffix
            if (suffix == "+B") onSuffixProjection(current, projected)
            MutationPresence.Present(projected)
        }
    }
}

private class RevisionServer(
    private val firstFailure: Throwable? = null,
    private val canonicalTarget: MutationsTestKey? = null,
    private val holdSecondPush: Boolean = false,
) : MutationServer<MutationsTestKey, String> {
    val firstPushEntered = CompletableDeferred<Unit>()
    val releaseFirstPush = CompletableDeferred<Unit>()
    val secondPushEntered = CompletableDeferred<Unit>()
    val releaseSecondPush = CompletableDeferred<Unit>()
    val requests = mutableListOf<MutationPush<MutationsTestKey, String>>()
    private val acknowledgements = mutableMapOf<String, MutationAck<MutationsTestKey, String>>()
    var pushes = 0
        private set

    override suspend fun push(request: MutationPush<MutationsTestKey, String>): MutationAck<MutationsTestKey, String> {
        pushes += 1
        requests += request
        if (pushes == 1) {
            firstPushEntered.complete(Unit)
            releaseFirstPush.await()
        }
        if (pushes == 2) {
            secondPushEntered.complete(Unit)
            if (holdSecondPush) releaseSecondPush.await()
        }
        val ack = acknowledgements.getOrPut(request.idempotencyKey) {
            MutationPresentAck(
                authoritative = "confirmed:${(request.mine as MutationPresence.Present).value}",
                etag = "ack-$pushes",
                canonicalKey = canonicalTarget,
            )
        }
        if (pushes == 1) firstFailure?.let { throw it }
        return ack
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(request.retiredThroughSequence)
}

private fun revisionStore(
    rows: FakeSourceOfTruth<MutationsTestKey, String>,
    mutations: RevisionAppend,
    backend: RevisionServer,
    bookkeeper: Bookkeeper = FakeBookkeeper(),
    storage: MutationJournalStorage = InMemoryMutationJournalStorage(),
): MutationStore<MutationsTestKey, String> = mutationStore(
    registry = mutations.registry,
    server = backend,
    keyResolver = MutationsTestKeyResolver,
    valueCodecVersion = 1,
    valueCodec = FixtureStringArgsCodec,
) {
    persistence(rows)
    bookkeeper(bookkeeper)
    journalStorage(storage)
    fetcher { error("Acknowledgement revision tests must not fetch") }
}
