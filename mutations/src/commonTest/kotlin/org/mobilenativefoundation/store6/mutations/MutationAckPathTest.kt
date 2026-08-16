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
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class MutationAckPathTest {
    @Test
    fun ack_landsSotOrigin_withZeroAdditionalFetches() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("lands-sot")
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
            users.stream(key).test {
                awaitValue("base")
                users.mutate(key, mutation.ref, "optimistic")
                assertEquals(Origin.OVERLAY, awaitValue("optimistic").origin)
                val fetchesBeforeAck = backend.fetchCount

                users.drain(key)

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
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                bookkeeper(bookkeeper)
            }

        try {
            assertEquals("base", users.get(key))
            val fetchesBeforeAck = backend.fetchCount
            users.invalidate(key)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            users.mutate(key, mutation.ref, "confirmed")

            users.drain(key)

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
                    MutationPresentAck(
                        authoritative = "confirmed:$value",
                        etag = "server-etag",
                        canonicalKey = null,
                    )
                }
            }
        val key = MutationsTestKey("retire")
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

            users.drain(key)

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
                    MutationPresentAck(
                        authoritative = "confirmed:$value",
                        etag = "server-etag",
                        canonicalKey = null,
                    )
                }
            }
        val key = MutationsTestKey("never-old")
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
            users.stream(key).test {
                awaitValue("base")
                users.mutate(key, mutation.ref, "pending")
                awaitValue("pending")

                users.drain(key)

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
                baseReader = { "base" },
            )
        val key = MutationsTestKey("ordering")
        engine.bind(RecordingWriteHandle(events))
        engine.mutate(key, mutation.ref, "pending")

        engine.drain(key)

        assertEquals(listOf("apply", "confirmFresh", "retire"), events)
    }

    // The sealed Present variant adopts through apply -> confirmFresh, then retires; the
    // accepted-state key-change signal follows the completed retirement.
    @Test
    fun presentAck_appliesConfirmsFreshThenRetires() = runTest {
        val events = mutableListOf<String>()
        val journal = RetireOrderingJournal(events)
        val mutation = RenameMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    MutationPresentAck(
                        authoritative = "authoritative:$value",
                        etag = "ack-etag",
                        canonicalKey = null,
                    )
                }
            }
        val applied = mutableListOf<Pair<String, String?>>()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                journal = journal,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
                absentAdoption = { fail("a Present acknowledgement must never use the clear door") },
            )
        val handle =
            object : StoreWriteHandle<MutationsTestKey, String> {
                override suspend fun apply(
                    key: MutationsTestKey,
                    value: String,
                ) {
                    events += "apply"
                    applied += value to null
                }

                override suspend fun markStale(key: MutationsTestKey) = Unit

                override suspend fun confirmFresh(
                    key: MutationsTestKey,
                    etag: String?,
                ) {
                    events += "confirmFresh"
                    applied += "confirmFresh" to etag
                }
            }
        engine.bind(handle)
        val key = MutationsTestKey("present-adoption")
        engine.mutate(key, mutation.ref, "pending")

        engine.drain(key)

        assertEquals(listOf("apply", "confirmFresh", "retire"), events)
        assertEquals("authoritative:pending", applied.first().first)
        assertEquals("ack-etag", applied.last().second)
        assertEquals(emptyList(), engine.pending(key))
        // The accepted-state handoff published this key's change after retirement.
        assertSame(key, engine.changes.replayCache.single())
    }

    // The sealed Absent variant adopts through the bound clear door, then retires; the
    // variant's type has no canonical key, so rekey-on-deletion is unrepresentable.
    @Test
    fun absentAck_clearsThenRetires_andHasNoCanonicalKey() = runTest {
        val events = mutableListOf<String>()
        val journal = ClearOrderingJournal(events)
        lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                deleteRef = delete(id = "delete", stales = noStales())
            }
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                journal = journal,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "resident" },
                absentAdoption = { events += "clear" },
            )
        engine.bind(FailingAdoptionHandle)
        val key = MutationsTestKey("absent-adoption")
        engine.mutate(key, deleteRef, Unit)

        engine.drain(key)

        assertEquals(listOf("clear", "retire"), events)
        assertEquals(emptyList(), engine.pending(key))
        assertSame(key, engine.changes.replayCache.single())
        val absentPush = backend.receivedPushes.single()
        assertSame(MutationPresence.Absent, absentPush.mine)
        // Compile-level (with MutationProtocolTest's exhaustive proof): the Absent variant
        // constructs from an etag alone — no canonicalKey exists to reference.
        when (val ack = backend.lastAck) {
            is MutationPresentAck -> fail("delete must be acknowledged with the Absent variant")
            is MutationAbsentAck -> assertNotNull(ack.etag)
            null -> fail("the push was never acknowledged")
        }
    }

    @Test
    fun presentAndAbsentAckVariants_followDistinctAdoptionPaths() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                rename =
                    mutator(
                        id = "rename",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
                deleteRef = delete(id = "delete", stales = noStales())
            }
        val adoptionLog = mutableListOf<String>()
        val engine =
            MutationEngine(
                registry = registry,
                server = echoingMutationServer(),
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
                absentAdoption = { key -> adoptionLog += "clear:${key.canonicalId()}" },
            )
        val handle =
            object : StoreWriteHandle<MutationsTestKey, String> {
                override suspend fun apply(
                    key: MutationsTestKey,
                    value: String,
                ) {
                    adoptionLog += "apply:${key.canonicalId()}"
                }

                override suspend fun markStale(key: MutationsTestKey) = Unit

                override suspend fun confirmFresh(
                    key: MutationsTestKey,
                    etag: String?,
                ) {
                    adoptionLog += "confirmFresh:${key.canonicalId()}"
                }
            }
        engine.bind(handle)
        val presentKey = MutationsTestKey("adopts-present")
        val absentKey = MutationsTestKey("adopts-absent")
        engine.mutate(presentKey, rename, "renamed")
        engine.mutate(absentKey, deleteRef, Unit)

        engine.drain(presentKey)
        engine.drain(absentKey)

        assertEquals(
            listOf("apply:adopts-present", "confirmFresh:adopts-present", "clear:adopts-absent"),
            adoptionLog,
        )
        assertEquals(emptyList(), engine.pending(presentKey))
        assertEquals(emptyList(), engine.pending(absentKey))
    }

    // A fetch that was in flight before the Absent acknowledgement may legally return its
    // pre-ack snapshot; every fetch BEGUN AFTER the acknowledgement returns
    // FetcherResult.Deleted — the backend coherence obligation MutationAbsentAck certifies.
    @Test
    fun absentAckWithPreAckInflightFetch_thenPostAckRefetchReturnsDeleted() = runTest {
        lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                deleteRef = delete(id = "delete", stales = noStales())
            }
        val backend = FakeBackend()
        val key = MutationsTestKey("deleted-entity")
        val users =
            mutationStore(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcherOfResult { backend.loadResult(it) }
            }

        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        try {
            assertEquals("base", users.get(key))

            // Pre-ack in-flight fetch: it enters the backend before the delete is even
            // enqueued and completes while the delete is still pending — every moment of its
            // flight precedes the acknowledgement, which the still-empty push log proves.
            backend.loadGate = {
                fetchEntered.complete(Unit)
                releaseFetch.await()
            }
            users.invalidate(key)
            val inflight =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { users.get(key, Freshness.MustBeFresh) }
                }
            fetchEntered.await()
            users.mutate(key, deleteRef, Unit)
            backend.loadGate = null
            releaseFetch.complete(Unit)
            // The fetch began pre-ack, so its Success snapshot is legal; with the delete now
            // pending, the overlay masks the read as projected absence. Either surface is
            // coherent — what matters is that the fetch completed before any push existed.
            inflight.await()
            assertEquals(emptyList(), backend.receivedPushes)

            users.drain(key)

            assertSame(MutationPresence.Absent, backend.receivedPushes.single().mine)
            assertEquals(emptyList(), users.pending(key))
            // Post-ack refetch: begun after the Absent acknowledgement, the backend returns
            // FetcherResult.Deleted and the waiter observes Missing — the coherence
            // obligation MutationAbsentAck certifies.
            val failure =
                assertFailsWith<StoreException> {
                    users.get(key, Freshness.MustBeFresh)
                }
            assertIs<StoreError.Missing>(failure.error)
        } finally {
            releaseFetch.complete(Unit)
            users.close()
        }
    }

    // Present capture reads bookkeeping status BEFORE the LocalOnly value, and the
    // captured metadata is the pre-value snapshot — it may match or lag the value but can never
    // lead it under Store's commit ordering.
    @Test
    fun presentCapture_readsStatusBeforeLocalOnlyAndMetadataNeverLeadsValue() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val log = mutableListOf<String>()
        val bookkeeper =
            ScriptedBookkeeper(
                log = log,
                statuses =
                    ArrayDeque(
                        listOf(
                            keyStatus(CaptureMeta(writtenAtEpochMillis = 42L, etag = "first")),
                            keyStatus(CaptureMeta(writtenAtEpochMillis = 99L, etag = "second")),
                        ),
                    ),
            )
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                bookkeeper = bookkeeper,
                keyResolver = MutationsTestKeyResolver,
                baseReader = {
                    log += "base"
                    "resident"
                },
            )
        engine.bind(CaptureNoopHandle)
        val key = MutationsTestKey("present-capture")
        engine.mutate(key, mutation.ref, "next")

        engine.drain(key)

        // Exactly one ordered bracket: status first, then the LocalOnly base read.
        assertEquals(listOf("status", "base"), log)
        val push = backend.receivedPushes.single()
        assertEquals("resident", assertIs<MutationPresence.Present<String>>(push.base).value)
        // The FIRST (pre-value) status was captured; the later status never entered the push.
        val meta = assertIs<StoreMeta>(push.baseMeta)
        assertEquals(42L, meta.writtenAtEpochMillis)
        assertEquals("first", meta.etag)
    }

    // Absence is accepted only from the exact loop status -> LocalOnly Missing -> status
    // with BOTH bracketing statuses carrying no metadata.
    @Test
    fun stableAbsentCapture_requiresBothBracketingStatusesWithoutMetadata() = runTest {
        lateinit var createRef: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                createRef =
                    create(
                        id = "create",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { value -> value }
            }
        val backend = FakeBackend()
        val log = mutableListOf<String>()
        val bookkeeper = ScriptedBookkeeper(log = log, statuses = ArrayDeque(listOf(null, null)))
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                bookkeeper = bookkeeper,
                keyResolver = MutationsTestKeyResolver,
                baseReader = {
                    log += "base"
                    null
                },
            )
        engine.bind(CaptureNoopHandle)
        val key = MutationsTestKey("stable-absent")
        engine.mutate(key, createRef, "created")

        engine.drain(key)

        assertEquals(listOf("status", "base", "status"), log)
        val push = backend.receivedPushes.single()
        // Absent is an existence precondition, not an unconditional write (Shared invariants).
        assertSame(MutationPresence.Absent, push.base)
        assertNull(push.baseMeta)
        assertEquals("created", assertIs<MutationPresence.Present<String>>(push.mine).value)
    }

    @Test
    fun absentCapture_retriesWhenEitherBracketingStatusHasMetadata() = runTest {
        // Leading bracket carries metadata: the window is open and the loop must retry.
        assertAbsentCaptureRetries(
            statuses =
                ArrayDeque(
                    listOf(
                        keyStatus(CaptureMeta(writtenAtEpochMillis = 7L, etag = "leading")),
                        null,
                        null,
                        null,
                    ),
                ),
        )
        // Trailing bracket carries metadata: same rejection, same retry.
        assertAbsentCaptureRetries(
            statuses =
                ArrayDeque(
                    listOf(
                        null,
                        keyStatus(CaptureMeta(writtenAtEpochMillis = 8L, etag = "trailing")),
                        null,
                        null,
                    ),
                ),
        )
    }

    // A FetcherResult.Deleted window — the destructive clear that forgets freshness —
    // cannot make a mid-window read pass for stable absence; the loop rejects the bracket whose
    // leading status still carried metadata and re-reads. The post-ack facade-level Deleted flow
    // is proven by absentAckWithPreAckInflightFetch_thenPostAckRefetchReturnsDeleted.
    @Test
    fun fetchDeletionCannotCreateFalseStableAbsence() = runTest {
        assertWindowCannotCreateFalseStableAbsence { bookkeeper, key ->
            bookkeeper.forget(key)
        }
    }

    // A Store.clear(key) window shares the loop, not only the facade interlock.
    @Test
    fun keyClearCannotCreateFalseStableAbsence() = runTest {
        assertWindowCannotCreateFalseStableAbsence { bookkeeper, key ->
            bookkeeper.forget(key)
        }
    }

    @Test
    fun namespaceClearCannotCreateFalseStableAbsence() = runTest {
        assertWindowCannotCreateFalseStableAbsence { bookkeeper, _ ->
            bookkeeper.forgetNamespace(StoreNamespace("mutations"))
        }
    }

    @Test
    fun clearAllCannotCreateFalseStableAbsence() = runTest {
        assertWindowCannotCreateFalseStableAbsence { bookkeeper, _ ->
            bookkeeper.forgetAll()
        }
    }

    @Test
    fun ackFailure_leavesIntentPending_andKeepsProjection() = runTest {
        val mutation = RenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("push-failure")
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
            val mutationId = users.mutate(key, mutation.ref, "optimistic")
            backend.offline = true

            users.drain(key)

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
                hostile =
                    mutator(
                        id = "hostile",
                        version = 1,
                        codec = inertArgsCodec<Unit>(),
                        stales = noStales(),
                    ) { _, _ -> error("projection failed") }
                healthy =
                    mutator(
                        id = "healthy",
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
                baseReader = { "base" },
            )
        val key = MutationsTestKey("hostile-head")
        engine.bind(RecordingWriteHandle(mutableListOf()))
        val hostileId = engine.mutate(key, hostile, Unit)
        val healthyId = engine.mutate(key, healthy, "tail")

        engine.drain(key)

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
                healthy =
                    mutator(
                        id = "healthy",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val journal = InMemoryMutationJournal<String>()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                journal = journal,
                baseReader = { "base" },
            )
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

        engine.drain(key)

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
        var capturedBase: String? = null
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                baseReader = { capturedBase },
            )
        val key = MutationsTestKey("adoption-failure")
        val store = store<MutationsTestKey, String> { fetcher { backend.load(it) } }
        capturedBase = store.get(key)
        engine.bind(assertNotNull(store.runtime()).writeHandle)
        val mutationId = engine.mutate(key, mutation.ref, "pending")
        store.close()

        val failure =
            assertFailsWith<IllegalStateException> {
                engine.drain(key)
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
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
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
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }
        users.close()

        val mutateFailure =
            assertFailsWith<IllegalStateException> {
                users.mutate(key, mutation.ref, "never-appended")
            }
        val keyedDrainFailure =
            assertFailsWith<IllegalStateException> {
                users.drain(key)
            }
        val globalDrainFailure =
            assertFailsWith<IllegalStateException> {
                users.drain()
            }
        val pendingFailure =
            assertFailsWith<IllegalStateException> {
                users.pending(key)
            }
        val pendingWritesFailure =
            assertFailsWith<IllegalStateException> {
                users.pendingWrites()
            }
        val deadLettersFailure =
            assertFailsWith<IllegalStateException> {
                users.deadLetters()
            }

        assertEquals("Store is closed.", mutateFailure.message)
        assertEquals("Store is closed.", keyedDrainFailure.message)
        assertEquals("Store is closed.", globalDrainFailure.message)
        assertEquals("Store is closed.", pendingFailure.message)
        assertEquals("Store is closed.", pendingWritesFailure.message)
        assertEquals("Store is closed.", deadLettersFailure.message)
    }

    @Test
    fun drain_progressivePrefixUsesServerEcho() = runTest {
        val mutation = AppendMutation()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    when (value) {
                        "base+A" ->
                            MutationPresentAck(
                                authoritative = "echo-A",
                                etag = "etag-a",
                                canonicalKey = null,
                            )
                        "echo-A+B" ->
                            MutationPresentAck(
                                authoritative = "echo-A+B",
                                etag = "etag-b",
                                canonicalKey = null,
                            )
                        else -> error("unexpected prefix $value")
                    }
                }
            }
        val key = MutationsTestKey("progressive-prefix")
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
            users.mutate(key, mutation.ref, "+A")
            users.mutate(key, mutation.ref, "+B")

            users.drain(key)

            assertEquals(listOf("base+A", "echo-A+B"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(key))
            assertEquals("echo-A+B", users.get(key, Freshness.LocalOnly))
        } finally {
            users.close()
        }
    }

    // Supersedes the walking skeleton's absent-halt (drainOnce_nullPrefixStopsAndLeaves
    // DeleteAndCreatePending): delete is drainable — a projected Absent
    // pushes with mine = Absent, the Absent acknowledgement adopts through clear, and a queued
    // create then re-creates over confirmed absence.
    @Test
    fun drain_deleteFlowsThroughAbsentAckAndCreateRecreates() = runTest {
        lateinit var update: MutatorRef<MutationsTestKey, String, String>
        lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>
        lateinit var createRef: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                update =
                    mutator(
                        id = "update",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
                deleteRef = delete(id = "delete", stales = noStales())
                createRef =
                    mutator(
                        id = "create",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val key = MutationsTestKey("delete-then-create")
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
            users.mutate(key, update, "updated")
            users.mutate(key, deleteRef, Unit)
            users.mutate(key, createRef, "recreated")

            users.drain(key)

            assertEquals(listOf("updated", "recreated"), backend.pushedValues)
            assertEquals(3, backend.receivedPushes.size)
            assertSame(MutationPresence.Absent, backend.receivedPushes[1].mine)
            // The create pushed over confirmed absence: its base is the existence precondition.
            assertSame(MutationPresence.Absent, backend.receivedPushes[2].base)
            assertEquals(emptyList(), users.pending(key))
            assertEquals("recreated", users.get(key, Freshness.LocalOnly))
        } finally {
            users.close()
        }
    }

    @Test
    fun concurrentDrain_serializesReadAndAdoption() = runTest {
        val mutation = RenameMutation()
        val pushStarted = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val backend =
            FakeBackend().apply {
                pushBehavior = { _, value ->
                    pushStarted.complete(Unit)
                    releasePush.await()
                    MutationPresentAck(authoritative = value, etag = "etag", canonicalKey = null)
                }
            }
        val key = MutationsTestKey("concurrent-drain")
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
            val first =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    users.drain(key)
                }
            pushStarted.await()
            val second =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    users.drain(key)
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
            val mutationId = users.mutate(key, mutation.ref, "pending")

            val failure =
                assertFailsWith<CancellationException> {
                    users.drain(key)
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

    /**
     * The shared skeleton of the four clear/deletion-window proofs: a leading status that still
     * carries metadata brackets a missing value, so the loop must reject that read and re-read
     * before accepting stable absence. A single-bracket capture would accept the mid-window read
     * and push a false existence precondition.
     */
    private suspend fun assertWindowCannotCreateFalseStableAbsence(
        window: suspend (Bookkeeper, MutationsTestKey) -> Unit,
    ) {
        lateinit var createRef: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                createRef =
                    create(
                        id = "create",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { value -> value }
            }
        val backend = FakeBackend()
        val bookkeeper = MutationBookkeeper()
        val key = MutationsTestKey("window-under-test")
        bookkeeper.recordSuccess(key, CaptureMeta(writtenAtEpochMillis = 10L, etag = "seeded"))
        var baseReads = 0
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                bookkeeper = bookkeeper,
                keyResolver = MutationsTestKeyResolver,
                baseReader = {
                    baseReads += 1
                    if (baseReads == 1) {
                        // The racing operation lands between the leading status and this read:
                        // the value is already gone while the leading status still shows meta.
                        window(bookkeeper, key)
                    }
                    null
                },
            )
        engine.bind(CaptureNoopHandle)
        engine.mutate(key, createRef, "recreated")

        engine.drain(key)

        // Two brackets: the mid-window read was rejected, the clean re-read was accepted.
        assertEquals(2, baseReads)
        val push = backend.receivedPushes.single()
        assertSame(MutationPresence.Absent, push.base)
        assertNull(push.baseMeta)
    }

    private suspend fun assertAbsentCaptureRetries(statuses: ArrayDeque<KeyStatus?>) {
        lateinit var createRef: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                createRef =
                    create(
                        id = "create",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { value -> value }
            }
        val backend = FakeBackend()
        val log = mutableListOf<String>()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                bookkeeper = ScriptedBookkeeper(log = log, statuses = statuses),
                keyResolver = MutationsTestKeyResolver,
                baseReader = {
                    log += "base"
                    null
                },
            )
        engine.bind(CaptureNoopHandle)
        val key = MutationsTestKey("absent-retry")
        engine.mutate(key, createRef, "created")

        engine.drain(key)

        // First bracket rejected, second accepted: status/base/status twice over.
        assertEquals(listOf("status", "base", "status", "status", "base", "status"), log)
        assertSame(MutationPresence.Absent, backend.receivedPushes.single().base)
    }
}

private class RenameMutation {
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

private class AppendMutation {
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

private class RetireOrderingJournal(
    private val events: MutableList<String>,
) : StorageBackedMutationJournal<String>(storage = InMemoryMutationJournalStorage()) {
    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        assertEquals(listOf("apply", "confirmFresh"), events)
        super.retire(key, mutationId)
        events += "retire"
    }
}

private class ClearOrderingJournal(
    private val events: MutableList<String>,
) : StorageBackedMutationJournal<String>(storage = InMemoryMutationJournalStorage()) {
    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        assertEquals(listOf("clear"), events)
        super.retire(key, mutationId)
        events += "retire"
    }
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

/** An Absent acknowledgement must never adopt through the write handle. */
private object FailingAdoptionHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ): Unit = fail("an Absent acknowledgement must not call apply")

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ): Unit = fail("an Absent acknowledgement must not call confirmFresh")
}

private object CaptureNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
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

private class CaptureMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private fun keyStatus(meta: StoreMeta?): KeyStatus =
    KeyStatus(
        meta = meta,
        lastSuccessSequence = 1L,
        lastFailureAtEpochMillis = null,
        consecutiveFailures = 0,
        durablyStale = false,
    )

/**
 * Status reads come from a scripted queue and are logged; every other bookkeeping operation is
 * inert. WallClock-style scripting keeps the capture brackets deterministic.
 */
private class ScriptedBookkeeper(
    private val log: MutableList<String>,
    private val statuses: ArrayDeque<KeyStatus?>,
) : Bookkeeper {
    override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) = Unit

    override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) = Unit

    override suspend fun status(key: StoreKey): KeyStatus? {
        log += "status"
        return statuses.removeFirstOrNull()
    }

    override suspend fun forget(key: StoreKey) = Unit

    override suspend fun markStale(key: StoreKey) = Unit

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) = Unit

    override suspend fun advanceGlobalStaleWatermark() = Unit

    override suspend fun forgetNamespace(namespace: StoreNamespace) = Unit

    override suspend fun forgetAll() = Unit
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

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
