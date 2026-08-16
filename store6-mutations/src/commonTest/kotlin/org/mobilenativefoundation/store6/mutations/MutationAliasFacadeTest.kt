@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The same-process canonical alias facade and the facade conversion-error/liveness contract.
 * Most tests here exercise the in-memory preview — normalized full-pair redirects,
 * sequence-merged sibling re-homing, the lost-wakeup-free per-terminal-identity revision
 * signals, and explicit keyed facade routing.
 *
 * The public-facade projection-fence regression exercises the durable storage seam. The durable
 * model's broader contract is proven elsewhere:
 * - `MutationJournalContractTest.kt::aliasEdgesAndActivation_roundTripAcrossRestart`
 * - `MutationAckOrchestrationTest.kt::ackAliasActivationRebasesQueuedSourceAndTargetSiblings`
 * - `MutationConflictTest.kt::serverWinsCancellationAfterCommit_stillPublishesOverlayRevision`
 * - `MutationDrainParkingTest.kt::parkingCancellationAfterCommit_stillPublishesOverlayRevisionAndRebasesSuffix`
 * - `MutationDrainParkingTest` also covers the durable parks for the alias-protocol violations
 *   that halt with a normalized `PROTOCOL` carrier here.
 */
class MutationAliasFacadeTest {
    @Test
    fun presentAckRedirectsQueuedSiblingsAndFutureCalls() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        val provisional = MutationsTestKey("temp-1")
        val canonical = MutationsTestKey("real-1")
        val users = aliasMutationStore(mutations.registry, backend)

        try {
            // Sequences 1 and 2 queue at the provisional identity, 3 at the canonical target:
            // after the head's redirect activates, the canonical queue must flush 2 then 3 —
            // queued siblings from source and target merge by durable client sequence.
            users.mutate(provisional, mutations.rename, "draft")
            users.mutate(provisional, mutations.append, "+p")
            users.mutate(canonical, mutations.append, "+c")

            users.drain(provisional)

            assertEquals(
                listOf("temp-1", "real-1", "real-1"),
                backend.receivedPushes.map { push -> push.identity.canonicalId },
            )
            assertEquals(
                listOf(1L, 2L, 3L),
                backend.receivedPushes.map { push -> push.clientSequence },
            )
            assertEquals(listOf("draft", "draft+p", "draft+p+c"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(provisional))
            assertEquals(emptyList(), users.pendingWrites())

            // Future calls route without re-resolving anything at the stale source key.
            assertEquals("draft+p+c", users.get(provisional))
            val futureId = users.mutate(provisional, mutations.append, "+later")
            val row = users.pendingWrites().single()
            assertEquals("real-1", row.canonicalId)
            assertEquals("mutations", row.namespace)
            assertEquals(futureId, row.mutationId)
        } finally {
            users.close()
        }
    }

    @Test
    fun streamSwitchesFromProvisionalToCanonicalAfterActivation() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        val provisional = MutationsTestKey("temp-1")
        val canonical = MutationsTestKey("real-1")
        val users = aliasMutationStore(mutations.registry, backend)

        try {
            users.stream(provisional).test {
                assertEquals("base", awaitData().value)

                users.mutate(provisional, mutations.rename, "draft")
                // OVERLAY is the pending-write affordance: the provisional frame is fresh by
                // definition, not stale: pending UI keys on origin == OVERLAY.
                val optimistic = awaitOverlayValue("draft")
                assertEquals(Origin.OVERLAY, optimistic.origin)
                assertFalse(optimistic.isStale)

                users.drain(provisional)

                // The live provisional stream re-resolves on the alias-revision bump and swaps
                // to delegate.stream(canonical): the confirmed canonical frame arrives
                // with a non-overlay origin and no refetch of the provisional identity.
                val confirmed = awaitNonOverlayValue("draft")
                assertTrue(
                    confirmed.origin == Origin.SOT || confirmed.origin == Origin.MEMORY,
                    "expected canonical residence after switch, was ${confirmed.origin}",
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(emptyList(), users.pending(provisional))
            assertEquals("draft", users.get(canonical))
        } finally {
            users.close()
        }
    }

    @Test
    fun allKeyedFacadeOperationsResolveTerminalIdentity() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        val provisional = MutationsTestKey("temp-1")
        val canonical = MutationsTestKey("real-1")
        val users = aliasMutationStore(mutations.registry, backend)

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)

            // get(P) serves the canonical residence.
            assertEquals("draft", users.get(provisional))

            // invalidate(P) marks the CANONICAL identity stale: a MaxAge read at the canonical
            // key must now block for a qualifying fetch.
            val fetchesBeforeInvalidate = backend.fetchCount
            users.invalidate(provisional)
            assertEquals("draft", users.get(canonical, Freshness.MaxAge(notOlderThan = Duration.ZERO)))
            assertEquals(fetchesBeforeInvalidate + 1, backend.fetchCount)

            // clear(P) removes the CANONICAL row: a LocalOnly read at the canonical key misses.
            users.clear(provisional)
            val missing =
                assertFailsWith<StoreException> {
                    users.get(canonical, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(missing.error)

            // mutate(P) journals at the canonical identity and pending(P) reads it back.
            val queuedId = users.mutate(provisional, mutations.rename, "requeued")
            val row = users.pendingWrites().single()
            assertEquals("real-1", row.canonicalId)
            assertEquals(queuedId, row.mutationId)
            assertEquals(
                listOf(queuedId),
                users.pending(provisional).map(PendingIntent::mutationId),
            )

            // drain(P) drains the canonical identity.
            users.drain(provisional)
            assertEquals("real-1", backend.receivedPushes.last().identity.canonicalId)
            assertEquals(emptyList(), users.pending(provisional))
        } finally {
            users.close()
        }
    }

    @Test
    fun selfAliasIsNoOpAndDuplicateTargetIsIdempotent() = runTest {
        val mutations = AliasMutationSet()

        // Self-alias: a Present ack whose canonical key equals the pushed key changes nothing.
        val selfBackend = FakeBackend()
        selfBackend.pushBehavior = { key, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "self-etag",
                canonicalKey = MutationsTestKey(key.canonicalId()),
            )
        }
        val selfKey = MutationsTestKey("self-1")
        val selfHarness = aliasHarness(mutations.registry, selfBackend)
        try {
            selfHarness.users.mutate(selfKey, mutations.rename, "kept")
            selfHarness.users.drain(selfKey)

            assertEquals(
                selfKey.identity(),
                selfHarness.engine.terminalIdentityOf(selfKey.identity()),
            )
            assertEquals(emptyList(), selfHarness.engine.drainFailuresForInspection())
            assertEquals("kept", selfHarness.users.get(selfKey))
            val laterId = selfHarness.users.mutate(selfKey, mutations.rename, "still-here")
            assertEquals("self-1", selfHarness.users.pendingWrites().single().canonicalId)
            assertEquals(laterId, selfHarness.users.pending(selfKey).single().mutationId)
        } finally {
            selfHarness.users.close()
        }

        // Duplicate equal edge: a retry of the SAME generation acknowledging the SAME canonical
        // target reuses the pending edge — idempotent, no protocol failure. (In-memory only:
        // the durable path never repushes an ACKED generation; this preview replays it.)
        val duplicateBackend = FakeBackend()
        duplicateBackend.redirectEcho("temp-2" to "real-2")
        val handle = ScriptableWriteHandle()
        val provisional = MutationsTestKey("temp-2")
        val duplicateHarness =
            aliasHarness(mutations.registry, duplicateBackend, engineWriteHandle = handle)
        try {
            duplicateHarness.users.mutate(provisional, mutations.rename, "draft")

            handle.applyFailure = IllegalStateException("adoption interrupted")
            assertFailsWith<IllegalStateException> {
                duplicateHarness.users.drain(provisional)
            }
            // Pending edge: not yet routed, but pinned for retry validation.
            assertEquals(
                provisional.identity(),
                duplicateHarness.engine.terminalIdentityOf(provisional.identity()),
            )
            assertEquals(1, duplicateHarness.users.pending(provisional).size)

            handle.applyFailure = null
            duplicateHarness.users.drain(provisional)

            assertEquals(
                KeyIdentity("mutations", "real-2"),
                duplicateHarness.engine.terminalIdentityOf(provisional.identity()),
            )
            assertEquals(emptyList(), duplicateHarness.engine.drainFailuresForInspection())
            assertEquals(2, duplicateBackend.receivedPushes.size)
            assertEquals(
                duplicateBackend.receivedPushes.first().idempotencyKey,
                duplicateBackend.receivedPushes.last().idempotencyKey,
            )
            assertEquals(emptyList(), duplicateHarness.users.pending(provisional))
        } finally {
            duplicateHarness.users.close()
        }
    }

    @Test
    fun chainsResolveTransitively() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1", "real-1" to "real-2")
        val provisional = MutationsTestKey("temp-1")
        val users = aliasMutationStore(mutations.registry, backend)

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional) // temp-1 -> real-1 activates.

            users.mutate(provisional, mutations.append, "+x") // journals at real-1
            users.drain(provisional) // real-1 -> real-2 activates.

            assertEquals(
                listOf("temp-1", "real-1"),
                backend.receivedPushes.map { push -> push.identity.canonicalId },
            )

            // Every keyed operation on the original provisional key now resolves through the
            // chain to the terminal identity: chains resolve transitively.
            assertEquals("draft+x", users.get(provisional))
            val queuedId = users.mutate(provisional, mutations.append, "+y")
            val row = users.pendingWrites().single()
            assertEquals("real-2", row.canonicalId)
            assertEquals(
                listOf(queuedId),
                users.pending(provisional).map(PendingIntent::mutationId),
            )
            users.drain(provisional)
            assertEquals("real-2", backend.receivedPushes.last().identity.canonicalId)
        } finally {
            users.close()
        }
    }

    @Test
    fun presentAckToActiveAlias_adoptsAtTerminalIdentity() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("source" to "canonical", "canonical" to "terminal")
        val source = MutationsTestKey("source")
        val canonical = MutationsTestKey("canonical")
        val users = aliasMutationStore(mutations.registry, backend)

        try {
            users.mutate(canonical, mutations.rename, "terminal-seed")
            users.drain(canonical)

            users.mutate(source, mutations.rename, "source-authoritative")
            users.drain(source)

            assertEquals(
                "source-authoritative",
                users.get(source, Freshness.LocalOnly),
            )
        } finally {
            users.close()
        }
    }

    @Test
    fun cycleRetargetAndRetryMismatchAreProtocolFailures() = runTest {
        suspend fun assertProtocolPark(
            journal: StorageBackedMutationJournal<String>,
            engine: MutationEngine<MutationsTestKey, String>,
            mutationId: String,
            expectedDetail: String,
        ) {
            val snapshot = journal.readDurableSnapshot()
            val intent = snapshot.intents.single { row -> row.mutationId == mutationId }
            val execution =
                snapshot.executions.single { row ->
                    row.clientId == intent.clientId && row.clientSequence == intent.clientSequence
                }
            val failures =
                snapshot.failures.filter { row ->
                    row.clientId == intent.clientId && row.clientSequence == intent.clientSequence
                }
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.PARKED,
                execution.phase,
            )
            val activeFailureId =
                execution.activeFailureId
                    ?: throw AssertionError("expected PARKED execution to link an active failure")
            val failure = failures.single { row -> row.failureId == activeFailureId }
            assertEquals(failure.failureId, execution.activeFailureId)
            assertEquals(MutationFailureKind.PROTOCOL, failure.kind)
            assertEquals(expectedDetail, failure.detail)
            assertEquals(1, execution.attempt)
            assertTrue(execution.lastAttemptAt != null)
            assertEquals(
                1,
                failures.count { row ->
                    row.kind == MutationFailureKind.PROTOCOL && row.detail == expectedDetail
                },
            )
            assertEquals(null, execution.retiredAt)
            assertTrue(
                snapshot.acks.none { row ->
                    row.clientId == intent.clientId && row.clientSequence == intent.clientSequence
                },
            )
            assertTrue(
                snapshot.effects.none { row ->
                    row.clientId == intent.clientId && row.clientSequence == intent.clientSequence
                },
            )
            assertTrue(
                snapshot.aliases.none { row ->
                    row.createdByClientId == intent.clientId &&
                        row.createdBySequence == intent.clientSequence
                },
            )
            assertTrue(
                snapshot.tombstones.none { row ->
                    row.createdByClientId == intent.clientId &&
                        row.createdBySequence == intent.clientSequence
                },
            )
            assertTrue(engine.pendingWrites().none { row -> row.mutationId == mutationId })
            val deadLetter = engine.deadLetters().single { row -> row.mutationId == mutationId }
            assertEquals(1, deadLetter.attempts)
            assertEquals(MutationFailureKind.PROTOCOL, deadLetter.failure.kind)
            assertEquals(expectedDetail, deadLetter.failure.detail)
        }

        val armFailures = mutableListOf<String>()
        suspend fun runArm(
            label: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (failure: AssertionError) {
                armFailures += "$label: ${failure.message.orEmpty()}"
            }
        }

        // Cycle: establish one ACTIVE durable edge, then park a head whose target closes it.
        runArm("cycle") {
            val mutations = AliasMutationSet()
            val storage = InMemoryMutationJournalStorage()
            val journal =
                StorageBackedMutationJournal<String>(
                    storage = storage,
                    registrations = mutations.registry.registrations,
                    hydrateOnFirstUse = true,
                )
            val backend = FakeBackend()
            backend.pushBehavior = { key, value ->
                MutationPresentAck(
                    authoritative = value,
                    etag = "etag-$value",
                    canonicalKey =
                        when {
                            key.canonicalId() == "temp-a" -> MutationsTestKey("real-a")
                            value == "cycle-head" -> MutationsTestKey("temp-a")
                            else -> null
                        },
                )
            }
            val handle = ScriptableWriteHandle()
            val engine =
                MutationEngine(
                    registry = mutations.registry,
                    server = backend,
                    journal = journal,
                    keyResolver = MutationsTestKeyResolver,
                    valueCodecVersion = 1,
                    valueCodec = FixtureStringArgsCodec,
                    baseReader = { "base" },
                )
            engine.bind(handle)
            val source = MutationsTestKey("temp-a")
            val target = MutationsTestKey("real-a")

            engine.mutate(source, mutations.rename, "cycle-seed")
            engine.drain(source)
            assertEquals(target.identity(), engine.terminalIdentityOf(source.identity()))
            val appliesBeforeViolation = handle.applied.toList()
            val subject = engine.mutate(target, mutations.rename, "cycle-head")
            val suffix = engine.mutate(target, mutations.rename, "cycle-suffix")

            engine.drain(target)

            assertProtocolPark(journal, engine, subject, ALIAS_FAILURE_DETAIL_CYCLE)
            val after = journal.readDurableSnapshot()
            val suffixIntent = after.intents.single { row -> row.mutationId == suffix }
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.RETIRED,
                after.executions.single { row ->
                    row.clientId == suffixIntent.clientId &&
                        row.clientSequence == suffixIntent.clientSequence
                }.phase,
            )
            assertEquals(appliesBeforeViolation + ("real-a" to "cycle-suffix"), handle.applied)
            assertTrue(handle.applied.none { (_, value) -> value == "cycle-head" })
            assertEquals(1, after.aliases.size)
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationAliasState.ACTIVE,
                after.aliases.single().state,
            )
            assertEquals(emptyList(), engine.pending(target))
            assertEquals(1, engine.deadLetters().size)
            val pushCount = backend.receivedPushes.size
            engine.drain(target)
            assertEquals(pushCount, backend.receivedPushes.size)
        }

        // Retarget: a separate durable client legally leaves the first edge PENDING at ACKED.
        // This client can then prove that a different generation cannot replace that target.
        runArm("retarget") {
            val mutations = AliasMutationSet()
            val storage = InMemoryMutationJournalStorage()
            val seedJournal =
                StorageBackedMutationJournal<String>(
                    storage = storage,
                    registrations = mutations.registry.registrations,
                    clientId = "retarget-seed-client",
                    hydrateOnFirstUse = true,
                )
            val seedBackend = FakeBackend()
            seedBackend.redirectEcho("temp-b" to "real-b1")
            val seedHandle = ScriptableWriteHandle()
            seedHandle.applyFailure = IllegalStateException("hold pending alias at ACKED")
            val seedEngine =
                MutationEngine(
                    registry = mutations.registry,
                    server = seedBackend,
                    journal = seedJournal,
                    keyResolver = MutationsTestKeyResolver,
                    valueCodecVersion = 1,
                    valueCodec = FixtureStringArgsCodec,
                    baseReader = { "base" },
                    clientId = "retarget-seed-client",
                )
            seedEngine.bind(seedHandle)
            val source = MutationsTestKey("temp-b")
            seedEngine.mutate(source, mutations.rename, "retarget-seed")
            assertFailsWith<IllegalStateException> { seedEngine.drain(source) }
            val seeded = seedJournal.readDurableSnapshot()
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.ACKED,
                seeded.executions.single().phase,
            )
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationAliasState.PENDING,
                seeded.aliases.single().state,
            )

            val journal =
                StorageBackedMutationJournal<String>(
                    storage = storage,
                    registrations = mutations.registry.registrations,
                    hydrateOnFirstUse = true,
                )
            val backend = FakeBackend()
            backend.pushBehavior = { _, value ->
                MutationPresentAck(
                    authoritative = value,
                    etag = "etag-$value",
                    canonicalKey =
                        when (value) {
                            "retarget-head" -> MutationsTestKey("real-b2")
                            "retarget-suffix" -> MutationsTestKey("real-b1")
                            else -> null
                        },
                )
            }
            val handle = ScriptableWriteHandle()
            val engine =
                MutationEngine(
                    registry = mutations.registry,
                    server = backend,
                    journal = journal,
                    keyResolver = MutationsTestKeyResolver,
                    valueCodecVersion = 1,
                    valueCodec = FixtureStringArgsCodec,
                    baseReader = { "base" },
                )
            engine.bind(handle)
            val subject = engine.mutate(source, mutations.rename, "retarget-head")
            val suffix = engine.mutate(source, mutations.rename, "retarget-suffix")

            engine.drain(source)

            assertProtocolPark(journal, engine, subject, ALIAS_FAILURE_DETAIL_RETARGET)
            val after = journal.readDurableSnapshot()
            val suffixIntent = after.intents.single { row -> row.mutationId == suffix }
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.RETIRED,
                after.executions.single { row ->
                    row.clientId == suffixIntent.clientId &&
                        row.clientSequence == suffixIntent.clientSequence
                }.phase,
            )
            assertEquals(listOf("real-b1" to "retarget-suffix"), handle.applied)
            assertTrue(handle.applied.none { (_, value) -> value == "retarget-head" })
            assertEquals(1, after.aliases.size)
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationAliasState.ACTIVE,
                after.aliases.single().state,
            )
            assertEquals(emptyList(), engine.pendingWrites())
            assertEquals(1, engine.deadLetters().size)
            val pushCount = backend.receivedPushes.size
            engine.drain(source)
            assertEquals(pushCount, backend.receivedPushes.size)
        }

        // Retry mismatch: the first target is observed, but its ACK transaction rolls back.
        // The exact generation replays once; changing its target must park before any receipt.
        runArm("retry-mismatch") {
            val mutations = AliasMutationSet()
            val backing = InMemoryMutationJournalStorage()
            var failAckTransaction = true
            val storage =
                object :
                    org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage {
                    override suspend fun <R> transaction(
                        block: (
                            org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction,
                        ) -> R,
                    ): R =
                        backing.transaction { transaction ->
                            var insertedAck = false
                            val observing =
                                object :
                                    org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction by
                                        transaction {
                                    override fun insertAck(
                                        record: org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord,
                                    ) {
                                        transaction.insertAck(record)
                                        insertedAck = true
                                    }
                                }
                            val result = block(observing)
                            if (failAckTransaction && insertedAck) {
                                failAckTransaction = false
                                throw IllegalStateException("ack transaction interrupted")
                            }
                            result
                        }
                }
            val journal =
                StorageBackedMutationJournal<String>(
                    storage = storage,
                    registrations = mutations.registry.registrations,
                    hydrateOnFirstUse = true,
                )
            val backend = FakeBackend()
            var target = "real-c1"
            backend.pushBehavior = { _, value ->
                MutationPresentAck(
                    authoritative = value,
                    etag = "etag-$value",
                    canonicalKey =
                        if (value == "retry-head") MutationsTestKey(target) else null,
                )
            }
            val handle = ScriptableWriteHandle()
            val engine =
                MutationEngine(
                    registry = mutations.registry,
                    server = backend,
                    journal = journal,
                    keyResolver = MutationsTestKeyResolver,
                    valueCodecVersion = 1,
                    valueCodec = FixtureStringArgsCodec,
                    baseReader = { "base" },
                )
            engine.bind(handle)
            val source = MutationsTestKey("temp-c")
            val subject = engine.mutate(source, mutations.rename, "retry-head")
            val suffix = engine.mutate(source, mutations.rename, "retry-suffix")

            assertFailsWith<IllegalStateException> { engine.drain(source) }
            assertFalse(failAckTransaction)
            val rolledBack = journal.readDurableSnapshot()
            val subjectIntent = rolledBack.intents.single { row -> row.mutationId == subject }
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.INFLIGHT,
                rolledBack.executions.single { row ->
                    row.clientId == subjectIntent.clientId &&
                        row.clientSequence == subjectIntent.clientSequence
                }.phase,
            )
            assertTrue(
                rolledBack.acks.none { row -> row.clientSequence == subjectIntent.clientSequence },
            )
            assertTrue(rolledBack.aliases.isEmpty())
            assertTrue(journal.runtimeSnapshot().aliases.isEmpty())
            target = "real-c2"

            engine.drain(source)

            assertEquals(
                backend.receivedPushes[0].idempotencyKey,
                backend.receivedPushes[1].idempotencyKey,
            )
            assertProtocolPark(
                journal,
                engine,
                subject,
                ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH,
            )
            val after = journal.readDurableSnapshot()
            val suffixIntent = after.intents.single { row -> row.mutationId == suffix }
            assertEquals(
                org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.RETIRED,
                after.executions.single { row ->
                    row.clientId == suffixIntent.clientId &&
                        row.clientSequence == suffixIntent.clientSequence
                }.phase,
            )
            assertEquals(listOf("temp-c" to "retry-suffix"), handle.applied)
            assertTrue(handle.applied.none { (_, value) -> value == "retry-head" })
            assertTrue(after.aliases.isEmpty())
            assertEquals(emptyList(), engine.pending(source))
            assertEquals(1, engine.deadLetters().size)
            val pushCount = backend.receivedPushes.size
            engine.drain(source)
            assertEquals(pushCount, backend.receivedPushes.size)
        }

        assertEquals(emptyList(), armFailures, armFailures.joinToString(separator = "\n"))
    }

    @Test
    fun namespaceCollisionAndCrossNamespaceAliasAreRejected() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.pushBehavior = { key, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "etag",
                canonicalKey =
                    when (key.canonicalId()) {
                        // The cross-namespace claim: same canonical id, foreign namespace.
                        "temp-x" -> MutationsTestKey("real-1", StoreNamespace("foreign"))
                        "temp-1" -> MutationsTestKey("real-1")
                        else -> null
                    },
            )
        }
        val harness = aliasHarness(mutations.registry, backend)
        val users = harness.users

        try {
            // Cross-namespace rejection: normalized PROTOCOL failure, intent halts, no adoption.
            val crossKey = MutationsTestKey("temp-x")
            val crossIntent = users.mutate(crossKey, mutations.rename, "never-adopted")
            users.drain(crossKey)

            val failure = harness.engine.drainFailuresForInspection().single()
            assertEquals(MutationFailureKind.PROTOCOL, failure.kind)
            assertEquals(ALIAS_FAILURE_DETAIL_CROSS_NAMESPACE, failure.detail)
            assertEquals(
                listOf(crossIntent),
                users.pending(crossKey).map(PendingIntent::mutationId),
            )
            assertEquals(
                crossKey.identity(),
                harness.engine.terminalIdentityOf(crossKey.identity()),
            )
            val neverAdopted =
                assertFailsWith<StoreException> {
                    users.get(crossKey, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(neverAdopted.error)

            // No canonical-id collision across namespaces: aliasing (mutations, temp-1) leaves
            // the full pair (foreign, temp-1) unrouted — durable identity is the exact pair.
            val provisional = MutationsTestKey("temp-1")
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)
            assertEquals(
                KeyIdentity("mutations", "real-1"),
                harness.engine.terminalIdentityOf(provisional.identity()),
            )
            val foreignTwin = MutationsTestKey("temp-1", StoreNamespace("foreign"))
            assertEquals(
                foreignTwin.identity(),
                harness.engine.terminalIdentityOf(foreignTwin.identity()),
            )
            val foreignIntent = users.mutate(foreignTwin, mutations.rename, "independent")
            val foreignRow =
                users.pendingWrites().single { row -> row.mutationId == foreignIntent }
            assertEquals("foreign", foreignRow.namespace)
            assertEquals("temp-1", foreignRow.canonicalId)
        } finally {
            users.close()
        }
    }

    @Test
    fun streamResolverFailureEmitsConversionAndRetriesOnAliasSignalWithoutCompleting() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        var resolverFailure: Throwable? = null
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverFailure?.let { throw it }
                        MutationsTestKeyResolver.resolve(identity)
                    },
            )
        val users = harness.users
        val provisional = MutationsTestKey("temp-1")

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)
            // Force the alias route through the resolver: drop the cached canonical K and make
            // the resolver fail.
            harness.engine.clearLiveKeyCache()
            val boom = IllegalStateException("canonical lookup offline")
            resolverFailure = boom

            users.stream(provisional).test {
                // Exactly one sanctioned conversion error: live, no delegation to the stale
                // source key, no completion.
                val error = assertIs<StoreResult.Error>(awaitItem())
                val conversion = assertIs<StoreError.Conversion>(error.error)
                assertSame(boom, conversion.cause)
                assertFalse(error.servedStale)
                expectNoEvents()

                // A new collection attempts resolution immediately and fails independently.
                users.stream(provisional).test {
                    val freshAttempt = assertIs<StoreResult.Error>(awaitItem())
                    assertIs<StoreError.Conversion>(freshAttempt.error)
                    cancelAndIgnoreRemainingEvents()
                }

                // A failing non-stream facade attempt bumps the identity's revision: the waiting
                // stream retries once and re-emits exactly one conversion error.
                assertFailsWith<StoreException> { users.get(provisional) }
                assertIs<StoreError.Conversion>(assertIs<StoreResult.Error>(awaitItem()).error)
                expectNoEvents()

                // A successful validated resolution wakes the stream: it resolves the terminal
                // identity and collects the canonical delegate stream.
                resolverFailure = null
                assertEquals("draft", users.get(provisional))
                val confirmed = awaitNonOverlayValue("draft")
                assertTrue(
                    confirmed.origin == Origin.SOT || confirmed.origin == Origin.MEMORY,
                    "expected canonical residence after retry, was ${confirmed.origin}",
                )
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            users.close()
        }
    }

    @Test
    fun suspendingFacadeResolverFailureThrowsConversion() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        var resolverFailure: Throwable? = null
        var resolveToNull = false
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverFailure?.let { throw it }
                        if (resolveToNull) null else MutationsTestKeyResolver.resolve(identity)
                    },
            )
        val users = harness.users
        val provisional = MutationsTestKey("temp-1")

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)
            harness.engine.clearLiveKeyCache()
            val boom = IllegalStateException("canonical lookup offline")
            resolverFailure = boom

            // One resolution attempt each; the sanctioned conversion-backed exception retains
            // the thrown cause in the immediate public exception only.
            val fromGet = assertFailsWith<StoreException> { users.get(provisional) }
            assertIs<StoreError.Conversion>(fromGet.error)
            assertSame(boom, fromGet.cause)

            val fromInvalidate = assertFailsWith<StoreException> { users.invalidate(provisional) }
            assertIs<StoreError.Conversion>(fromInvalidate.error)

            // Keyed drain parks rather than throws: an unresolvable pre-ack terminal records
            // the normalized IDENTITY carrier and returns normally. The durable engine converts
            // such a halt into a park.
            val failuresBeforeDrain = harness.engine.drainFailuresForInspection().size
            users.drain(provisional)
            val drainFailure = harness.engine.drainFailuresForInspection().last()
            assertEquals(failuresBeforeDrain + 1, harness.engine.drainFailuresForInspection().size)
            assertEquals(MutationFailureKind.IDENTITY, drainFailure.kind)
            assertEquals(DRAIN_FAILURE_DETAIL_KEYED_TERMINAL_UNRESOLVED, drainFailure.detail)

            // Resolver null has no cause.
            resolverFailure = null
            resolveToNull = true
            val fromClear = assertFailsWith<StoreException> { users.clear(provisional) }
            assertIs<StoreError.Conversion>(fromClear.error)
            assertEquals(null, fromClear.cause)
        } finally {
            users.close()
        }
    }

    @Test
    fun mutateResolverFailureFailsBeforeJournalAppend() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        var resolverFailure: Throwable? = null
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverFailure?.let { throw it }
                        MutationsTestKeyResolver.resolve(identity)
                    },
            )
        val users = harness.users
        val provisional = MutationsTestKey("temp-1")

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)
            harness.engine.clearLiveKeyCache()
            resolverFailure = IllegalStateException("canonical lookup offline")

            // mutate resolves BEFORE the append: failure creates no intent anywhere.
            val failure =
                assertFailsWith<StoreException> {
                    users.mutate(provisional, mutations.append, "+never")
                }
            assertIs<StoreError.Conversion>(failure.error)
            assertEquals(emptyList(), users.pendingWrites())
            assertEquals(emptyList(), users.pending(provisional))
        } finally {
            users.close()
        }
    }

    @Test
    fun pendingQueriesDurableIdentityWithoutResolvingK() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        var resolverCalls = 0
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverCalls += 1
                        MutationsTestKeyResolver.resolve(identity)
                    },
            )
        val users = harness.users
        val provisional = MutationsTestKey("temp-1")
        val canonical = MutationsTestKey("real-1")

        try {
            users.mutate(provisional, mutations.rename, "draft")
            users.drain(provisional)
            val queuedId = users.mutate(canonical, mutations.append, "+queued")
            harness.engine.clearLiveKeyCache()

            // pending(P) follows the alias as durable identity pairs only: no K is
            // reconstructed, so a dead resolver cannot fail this inspection.
            val rows = users.pending(provisional)
            assertEquals(listOf(queuedId), rows.map(PendingIntent::mutationId))
            assertEquals("real-1", rows.single().canonicalId)
            assertEquals(0, resolverCalls)
        } finally {
            users.close()
        }
    }

    @Test
    fun cancellationAfterAliasCommit_stillPublishesRevisionAndSwitchesLiveStream() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        val journal = InMemoryMutationJournal<String>()
        val handle = ScriptableWriteHandle()
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                journal = journal,
                engineWriteHandle = handle,
                nonSuspendingBaseValue = "base",
            )
        val engine = harness.engine
        val users = harness.users
        val blockerKey = MutationsTestKey("alias-signal-blocker")
        val provisional = MutationsTestKey("temp-1")
        val canonical = MutationsTestKey("real-1")
        val provisionalObserved = CompletableDeferred<StoreKey>()
        val canonicalObserved = CompletableDeferred<StoreKey>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.changes.collect { key ->
                    when {
                        key === provisional -> provisionalObserved.complete(key)
                        key === canonical || key.canonicalId() == "real-1" ->
                            canonicalObserved.complete(key)
                    }
                }
            }

        try {
            users.stream(provisional).test {
                assertEquals("base", awaitData().value)

                // The blocker's committed enqueue signal occupies the replay buffer so the
                // drain's post-commit emission must suspend inside the NonCancellable handoff —
                // the same staging as the two preserved accepted-state regressions,
                // MutationOverlayTest.cancelledMutate_afterAppendStillPublishesKeyChange and
                // cancelledDrain_afterRetireStillPublishesKeyChange.
                users.mutate(blockerKey, mutations.rename, "first")
                // Stage the provisional intent without an enqueue signal.
                journal.append(
                    provisional.identity(),
                    JournalEntry(
                        mutationId = "alias-intent",
                        mutatorId = mutations.rename.id,
                        args = "draft",
                    ),
                )

                val cancelledDrain =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.drain(provisional)
                    }
                assertFalse(cancelledDrain.isCompleted)

                // The in-memory commit and the stateful revision handoff completed BEFORE the
                // suspended emission: the alias is active and the revision is published even
                // though the drain caller is about to be cancelled: the accepted-state handoff
                // completes under NonCancellable.
                assertEquals(
                    canonical.identity(),
                    engine.terminalIdentityOf(provisional.identity()),
                )
                assertTrue(engine.aliasRevision(provisional.identity()).value > 0L)

                cancelledDrain.cancel()
                testScheduler.runCurrent()
                cancelledDrain.join()
                assertTrue(cancelledDrain.isCancelled)

                // Both accepted-state key-change signals survived the cancellation. Their
                // emissions completed inside the NonCancellable handoff before join() returned,
                // but the observing collector is dispatched separately, so await its
                // observations instead of asserting synchronous completion.
                provisionalObserved.await()
                canonicalObserved.await()

                // The live provisional stream switched to the canonical delegate stream.
                val confirmed = awaitNonOverlayValue("draft")
                assertTrue(confirmed.origin != Origin.OVERLAY)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(emptyList(), users.pending(provisional))
        } finally {
            try {
                collector.cancelAndJoin()
            } finally {
                users.close()
            }
        }
    }

    @Test
    fun aliasActivationCacheHandoff_neverExposesPartialSiblingProjection() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        val source = MutationsTestKey("handoff-source")
        val target = MutationsTestKey("handoff-target")
        val secondPushEntered = CompletableDeferred<Unit>()
        val releaseSecondPush = CompletableDeferred<Unit>()
        var pushCount = 0
        backend.pushBehavior = { key, value ->
            pushCount += 1
            if (pushCount == 2) {
                secondPushEntered.complete(Unit)
                releaseSecondPush.await()
            }
            MutationPresentAck(
                authoritative = value,
                etag = "etag-$pushCount",
                canonicalKey = target.takeIf { key.canonicalId() == source.canonicalId() },
            )
        }
        val journal = AliasRetireGateJournal(mutations.registry.registrations)
        val harness = aliasHarness(mutations.registry, backend, journal = journal)
        val users = harness.users

        val headId = users.mutate(source, mutations.rename, "head")
        val siblingId = users.mutate(source, mutations.append, "+tail")
        val drain =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.drain(source)
            }

        try {
            // Before the repair this pauses after ACTIVE routing publication but before retire;
            // after the repair it pauses on sibling push, after the one-snapshot handoff.
            select<Unit> {
                journal.retireEntered.onAwait { }
                secondPushEntered.onAwait { }
            }

            val pending = users.pending(source).map(PendingIntent::mutationId)
            var observed: StoreResult.Data<String>? = null
            users.stream(source, Freshness.LocalOnly).test {
                observed = awaitData()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf(siblingId), pending)
            assertEquals("head+tail", checkNotNull(observed).value)
            assertEquals(Origin.OVERLAY, checkNotNull(observed).origin)
            assertTrue(headId !in pending)
        } finally {
            journal.releaseRetire.complete(Unit)
            releaseSecondPush.complete(Unit)
            drain.await()
            users.close()
        }
    }

    @Test
    fun publicFacadeFreshAttachAfterAliasPublication_firstFrameIsCompleteOverlay() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        val source = MutationsTestKey("fence-source")
        val target = MutationsTestKey("fence-target")
        val secondPushEntered = CompletableDeferred<Unit>()
        val releaseSecondPush = CompletableDeferred<Unit>()
        var pushCount = 0
        backend.pushBehavior = { key, value ->
            pushCount += 1
            if (pushCount == 2) {
                secondPushEntered.complete(Unit)
                releaseSecondPush.await()
            }
            MutationPresentAck(
                authoritative = value,
                etag = "etag-$pushCount",
                canonicalKey = target.takeIf { key.canonicalId() == source.canonicalId() },
            )
        }
        val gatedStorage =
            AliasActivationCommitGateStorage(
                source = source.identity(),
                target = target.identity(),
            )
        val users =
            mutationStore(
                registry = mutations.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                journalStorage(gatedStorage)
            }

        users.mutate(source, mutations.rename, "head")
        users.mutate(source, mutations.append, "+tail")
        val drain =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.drain(source)
            }

        try {
            gatedStorage.commitEntered.await()
            val targetHeadObserved = CompletableDeferred<Unit>()
            val targetResidence =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    users.stream(target, Freshness.LocalOnly).collect { result ->
                        if (result is StoreResult.Data && result.value == "head") {
                            targetHeadObserved.complete(Unit)
                        }
                    }
                }
            try {
                targetHeadObserved.await()
                assertFalse(targetResidence.isCompleted)

                var freshAttach: Deferred<StoreResult<String>>? = null
                val stampObserver =
                    backgroundScope.launch(
                        context = Dispatchers.Unconfined,
                        start = CoroutineStart.UNDISPATCHED,
                    ) {
                        val applied = users.appliedProjectionStamp(target.identity())
                        users
                            .emittedProjectionStamp(target.identity())
                            .first { emitted -> emitted > applied.value }
                        freshAttach =
                            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                                users
                                    .stream(source, Freshness.LocalOnly)
                                    .first { result -> result is StoreResult.Data }
                            }
                        testScheduler.runCurrent()
                    }
                try {
                    gatedStorage.releaseCommit.complete(Unit)
                    secondPushEntered.await()

                    stampObserver.join()
                    val firstData =
                        assertIs<StoreResult.Data<String>>(
                            checkNotNull(freshAttach).await(),
                        )
                    assertEquals("head+tail", firstData.value)
                    assertEquals(Origin.OVERLAY, firstData.origin)
                } finally {
                    try {
                        stampObserver.cancelAndJoin()
                    } finally {
                        freshAttach?.cancelAndJoin()
                    }
                }
            } finally {
                targetResidence.cancelAndJoin()
            }
        } finally {
            gatedStorage.releaseCommit.complete(Unit)
            releaseSecondPush.complete(Unit)
            try {
                drain.await()
            } finally {
                users.close()
            }
        }
    }

    @Test
    fun freshSourceAttachWhenRuntimeAliasBecomesActive_firstFrameIsCompleteOverlay() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        val source = MutationsTestKey("runtime-fence-source")
        val target = MutationsTestKey("runtime-fence-target")
        val secondPushEntered = CompletableDeferred<Unit>()
        val releaseSecondPush = CompletableDeferred<Unit>()
        var pushCount = 0
        backend.pushBehavior = { key, value ->
            pushCount += 1
            if (pushCount == 2) {
                secondPushEntered.complete(Unit)
                releaseSecondPush.await()
            }
            MutationPresentAck(
                authoritative = value,
                etag = "etag-$pushCount",
                canonicalKey = target.takeIf { key.canonicalId() == source.canonicalId() },
            )
        }
        val gatedStorage =
            AliasActivationCommitGateStorage(
                source = source.identity(),
                target = target.identity(),
            )
        val journal =
            StorageBackedMutationJournal<String>(
                storage = gatedStorage,
                registrations = mutations.registry.registrations,
                hydrateOnFirstUse = true,
            )
        val harness = aliasHarness(mutations.registry, backend, journal = journal)
        val users = harness.users

        users.mutate(source, mutations.rename, "head")
        users.mutate(source, mutations.append, "+tail")
        val drain =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.drain(source)
            }

        try {
            gatedStorage.commitEntered.await()
            val targetHeadObserved = CompletableDeferred<Unit>()
            val targetResidence =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    users.stream(target, Freshness.LocalOnly).collect { result ->
                        if (result is StoreResult.Data && result.value == "head") {
                            targetHeadObserved.complete(Unit)
                        }
                    }
                }
            try {
                targetHeadObserved.await()
                assertFalse(targetResidence.isCompleted)

                var freshAttach: Deferred<StoreResult<String>>? = null
                val snapshotObserver =
                    backgroundScope.launch(
                        context = Dispatchers.Unconfined,
                        start = CoroutineStart.UNDISPATCHED,
                    ) {
                        journal.runtimeState.snapshots.first { snapshot ->
                            if (
                                snapshot.aliases[source.identity()]?.state ==
                                AliasEdgeState.ACTIVE
                            ) {
                                freshAttach =
                                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                                        users
                                            .stream(source, Freshness.LocalOnly)
                                            .first { result -> result is StoreResult.Data }
                                    }
                                testScheduler.runCurrent()
                                true
                            } else {
                                false
                            }
                        }
                    }
                try {
                    gatedStorage.releaseCommit.complete(Unit)
                    secondPushEntered.await()

                    snapshotObserver.join()
                    val firstData =
                        assertIs<StoreResult.Data<String>>(
                            checkNotNull(freshAttach).await(),
                        )
                    assertEquals("head+tail", firstData.value)
                    assertEquals(Origin.OVERLAY, firstData.origin)
                } finally {
                    try {
                        snapshotObserver.cancelAndJoin()
                    } finally {
                        freshAttach?.cancelAndJoin()
                    }
                }
            } finally {
                targetResidence.cancelAndJoin()
            }
        } finally {
            gatedStorage.releaseCommit.complete(Unit)
            releaseSecondPush.complete(Unit)
            try {
                drain.await()
            } finally {
                users.close()
            }
        }
    }

    @Test
    fun concurrentEnqueueDuringAliasActivation_rehomesAtTerminalIdentity() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        val source = MutationsTestKey("enqueue-source")
        val target = MutationsTestKey("enqueue-target")
        backend.redirectEcho(source.canonicalId() to target.canonicalId())
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val handle =
            object : StoreWriteHandle<MutationsTestKey, String> {
                override suspend fun apply(
                    key: MutationsTestKey,
                    value: String,
                ) {
                    applyEntered.complete(Unit)
                    releaseApply.await()
                }

                override suspend fun markStale(key: MutationsTestKey) = Unit

                override suspend fun confirmFresh(
                    key: MutationsTestKey,
                    etag: String?,
                ) = Unit
            }
        val journal = LateAppendGateJournal(mutations.registry.registrations)
        val harness =
            aliasHarness(
                registry = mutations.registry,
                backend = backend,
                journal = journal,
                engineWriteHandle = handle,
                nonSuspendingBaseValue = "base",
            )
        val users = harness.users

        users.mutate(source, mutations.rename, "head")
        val drain =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.drain(source)
            }
        applyEntered.await()

        journal.armLateAppend()
        val lateAppend =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                users.mutate(source, mutations.append, "+late")
            }
        journal.lateAppendEntered.await()

        // Current code can publish ACTIVE while append is paused after facade resolution. A
        // shared enqueue/activation gate may instead keep the drain queued behind that append.
        releaseApply.complete(Unit)
        testScheduler.runCurrent()
        journal.releaseLateAppend.complete(Unit)

        val lateId = lateAppend.await()
        drain.await()
        try {
            assertEquals(target.identity(), harness.engine.terminalIdentityOf(source.identity()))
            assertEquals(
                listOf(lateId),
                users.pending(source).map(PendingIntent::mutationId),
            )
            assertEquals(
                listOf(target.canonicalId()),
                users.pendingWrites().map(PendingIntent::canonicalId),
            )
            assertEquals("head+late", harness.engine.projectAll(target, "head"))
        } finally {
            users.close()
        }
    }

    @Test
    fun suspendingResolver_doesNotHoldGlobalMutationGate() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        val source = MutationsTestKey("resolver-source")
        val target = MutationsTestKey("resolver-target")
        backend.redirectEcho(source.canonicalId() to target.canonicalId())
        val storage = InMemoryMutationJournalStorage()
        val first =
            aliasHarness(
                registry = mutations.registry,
                backend = backend,
                journal =
                    StorageBackedMutationJournal(
                        storage = storage,
                        registrations = mutations.registry.registrations,
                        hydrateOnFirstUse = true,
                    ),
            )
        first.users.mutate(source, mutations.rename, "head")
        first.users.drain(source)
        first.users.close()

        val resolverEntered = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val reopened =
            aliasHarness(
                registry = mutations.registry,
                backend = backend,
                journal =
                    StorageBackedMutationJournal(
                        storage = storage,
                        registrations = mutations.registry.registrations,
                        hydrateOnFirstUse = true,
                    ),
                keyResolver =
                    MutationKeyResolver { identity ->
                        if (identity.canonicalId == target.canonicalId()) {
                            resolverEntered.complete(Unit)
                            releaseResolver.await()
                        }
                        MutationsTestKeyResolver.resolve(identity)
                    },
                nonSuspendingBaseValue = "base",
            )
        val blocked =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                reopened.users.mutate(source, mutations.append, "+blocked")
            }
        resolverEntered.await()
        val unrelated =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                reopened.users.mutate(
                    MutationsTestKey("resolver-unrelated"),
                    mutations.append,
                    "+free",
                )
            }
        testScheduler.runCurrent()

        try {
            assertTrue(unrelated.isCompleted)
        } finally {
            releaseResolver.complete(Unit)
            blocked.await()
            unrelated.await()
            reopened.users.close()
        }
    }

    @Test
    fun pendingInspection_usesOneRuntimeSnapshotAcrossAliasHandoff() = runTest {
        val staged = stageInspectionSplit(InspectionSplit.KEYED)
        try {
            assertTrue(
                staged.siblingId in
                    staged.users.pending(staged.source).map(PendingIntent::mutationId),
            )
        } finally {
            staged.drain.cancelAndJoin()
            staged.users.close()
        }
    }

    @Test
    fun pendingWritesInspection_usesOneRuntimeSnapshotAcrossAliasHandoff() = runTest {
        val staged = stageInspectionSplit(InspectionSplit.GLOBAL)
        try {
            assertTrue(
                staged.siblingId in staged.users.pendingWrites().map(PendingIntent::mutationId),
            )
        } finally {
            staged.drain.cancelAndJoin()
            staged.users.close()
        }
    }

    @Test
    fun closeWhileWaitingForResolver_cancelsStreamPromptlyAndReleasesRetrySubscription() = runTest {
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        backend.redirectEcho("temp-1" to "real-1")
        var resolverFailure: Throwable? = null
        val harness =
            aliasHarness(
                mutations.registry,
                backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverFailure?.let { throw it }
                        MutationsTestKeyResolver.resolve(identity)
                    },
            )
        val users = harness.users
        val provisional = MutationsTestKey("temp-1")
        val canonicalIdentity = KeyIdentity("mutations", "real-1")

        users.mutate(provisional, mutations.rename, "draft")
        users.drain(provisional)
        harness.engine.clearLiveKeyCache()
        resolverFailure = IllegalStateException("canonical lookup offline")

        val collected = mutableListOf<StoreResult<String>>()
        val streamCollector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                users.stream(provisional).collect { result -> collected += result }
            }
        testScheduler.runCurrent()

        // The stream emitted its one conversion error and is suspended on the retry
        // subscription for the terminal identity's revision signal.
        assertEquals(1, collected.size)
        assertIs<StoreError.Conversion>(assertIs<StoreResult.Error>(collected.single()).error)
        assertTrue(harness.engine.resolutionPulseSubscriptions(canonicalIdentity) >= 1)

        users.close()
        testScheduler.runCurrent()

        // close() cancels the waiting collector promptly and releases the retry subscription.
        assertTrue(streamCollector.isCompleted)
        assertTrue(streamCollector.isCancelled)
        assertEquals(0, harness.engine.resolutionPulseSubscriptions(canonicalIdentity))
        assertEquals(1, collected.size)
    }

    /**
     * The alias-facing ack-variant rule, standalone: a retry of one generation idempotency
     * key must return the same canonical target or the intent halts as a protocol violation.
     * The staging mirrors the retry-mismatch arm of
     * [cycleRetargetAndRetryMismatchAreProtocolFailures] with the ack-variant assertions.
     */
    @Test
    fun retryOfGenerationWithDifferentCanonicalTarget_isProtocolFailure() = runTest {
        val mutations = AliasMutationSet()
        val backing = InMemoryMutationJournalStorage()
        var failAckTransaction = true
        val storage =
            object : org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage {
                override suspend fun <R> transaction(
                    block: (
                        org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction,
                    ) -> R,
                ): R =
                    backing.transaction { transaction ->
                        var insertedAck = false
                        val observing =
                            object :
                                org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction by
                                    transaction {
                                override fun insertAck(
                                    record: org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord,
                                ) {
                                    transaction.insertAck(record)
                                    insertedAck = true
                                }
                            }
                        val result = block(observing)
                        if (failAckTransaction && insertedAck) {
                            failAckTransaction = false
                            throw IllegalStateException("ack transaction interrupted")
                        }
                        result
                    }
            }
        val journal =
            StorageBackedMutationJournal<String>(
                storage = storage,
                registrations = mutations.registry.registrations,
                hydrateOnFirstUse = true,
            )
        val backend = FakeBackend()
        var canonicalTarget = "real-1"
        backend.pushBehavior = { _, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "etag",
                canonicalKey = MutationsTestKey(canonicalTarget),
            )
        }
        val handle = ScriptableWriteHandle()
        val engine =
            MutationEngine(
                registry = mutations.registry,
                server = backend,
                journal = journal,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
                baseReader = { "base" },
            )
        engine.bind(handle)
        val provisional = MutationsTestKey("temp-1")

        // The first target is validated, but its acknowledgement transaction rolls back. The
        // durable generation remains INFLIGHT and therefore replays with the exact idempotency
        // key; the process-local target receipt still makes a different retry target illegal.
        val intent = engine.mutate(provisional, mutations.rename, "draft")
        assertFailsWith<IllegalStateException> { engine.drain(provisional) }
        assertFalse(failAckTransaction)
        val rolledBack = journal.readDurableSnapshot()
        val intentRow = rolledBack.intents.single { row -> row.mutationId == intent }
        assertEquals(
            org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.INFLIGHT,
            rolledBack.executions.single { row ->
                row.clientId == intentRow.clientId && row.clientSequence == intentRow.clientSequence
            }.phase,
        )
        assertTrue(rolledBack.acks.isEmpty())
        assertTrue(rolledBack.aliases.isEmpty())
        assertTrue(rolledBack.tombstones.isEmpty())
        assertTrue(journal.runtimeSnapshot().aliases.isEmpty())

        // The retry of the SAME idempotency key acknowledges a different canonical target.
        canonicalTarget = "real-9"
        engine.drain(provisional)

        assertEquals(2, backend.receivedPushes.size)
        assertEquals(
            backend.receivedPushes.first().idempotencyKey,
            backend.receivedPushes.last().idempotencyKey,
        )
        val snapshot = journal.readDurableSnapshot()
        val execution =
            snapshot.executions.single { row ->
                row.clientId == intentRow.clientId && row.clientSequence == intentRow.clientSequence
            }
        val failures =
            snapshot.failures.filter { row ->
                row.clientId == intentRow.clientId && row.clientSequence == intentRow.clientSequence
            }
        assertEquals(
            org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.PARKED,
            execution.phase,
        )
        val activeFailureId =
            execution.activeFailureId
                ?: throw AssertionError("expected PARKED execution to link an active failure")
        val failure = failures.single { row -> row.failureId == activeFailureId }
        assertEquals(failure.failureId, execution.activeFailureId)
        assertEquals(MutationFailureKind.PROTOCOL, failure.kind)
        assertEquals(ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH, failure.detail)
        assertEquals(1, execution.attempt)
        assertTrue(execution.lastAttemptAt != null)
        assertEquals(
            1,
            failures.count { row ->
                row.kind == MutationFailureKind.PROTOCOL &&
                    row.detail == ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH
            },
        )
        assertEquals(null, execution.retiredAt)
        assertTrue(snapshot.acks.isEmpty())
        assertTrue(snapshot.aliases.isEmpty())
        assertTrue(snapshot.tombstones.isEmpty())
        assertTrue(snapshot.effects.isEmpty())
        assertTrue(journal.runtimeSnapshot().aliases.isEmpty())
        assertTrue(handle.applied.isEmpty())
        assertTrue(engine.pendingWrites().none { row -> row.mutationId == intent })
        val deadLetter = engine.deadLetters().single()
        assertEquals(intent, deadLetter.mutationId)
        assertEquals(1, deadLetter.attempts)
        assertEquals(MutationFailureKind.PROTOCOL, deadLetter.failure.kind)
        assertEquals(ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH, deadLetter.failure.detail)
        assertEquals(
            provisional.identity(),
            engine.terminalIdentityOf(provisional.identity()),
        )
        engine.drain(provisional)
        assertEquals(2, backend.receivedPushes.size)
    }
}

// ---------------------------------------------------------------------------------------------
// Fixtures local to the alias facade tests.
// ---------------------------------------------------------------------------------------------

private class AliasMutationSet {
    lateinit var rename: MutatorRef<MutationsTestKey, String, String>
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            rename =
                mutator(
                    id = "rename",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, value -> MutationPresence.Present(value) }
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
}

/**
 * Scripts echo acknowledgements whose canonical key redirects each listed provisional id to its
 * canonical id; every other key acknowledges with an unchanged identity.
 */
private fun FakeBackend.redirectEcho(vararg redirects: Pair<String, String>) {
    val table = redirects.toMap()
    pushBehavior = { key, value ->
        MutationPresentAck(
            authoritative = value,
            etag = "server-etag",
            canonicalKey = table[key.canonicalId()]?.let(::MutationsTestKey),
        )
    }
}

/** The public entry point, used where no engine door is needed. */
private fun aliasMutationStore(
    registry: MutatorRegistry<MutationsTestKey, String>,
    backend: FakeBackend,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = registry,
        server = backend,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
    ) {
        fetcher { backend.load(it) }
    }

private class AliasHarness(
    val engine: MutationEngine<MutationsTestKey, String>,
    val users: MutationStore<MutationsTestKey, String>,
)

private class AliasActivationCommitGateStorage(
    private val source: KeyIdentity,
    private val target: KeyIdentity,
) : MutationJournalStorage {
    private val delegate = InMemoryMutationJournalStorage()
    val commitEntered = CompletableDeferred<Unit>()
    val releaseCommit = CompletableDeferred<Unit>()

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R {
        var activationObserved = false
        val result =
            delegate.transaction { transaction ->
                block(
                    object : MutationJournalTransaction by transaction {
                        override fun advanceAlias(record: MutationKeyAliasRecord) {
                            transaction.advanceAlias(record)
                            if (
                                record.state == MutationAliasState.ACTIVE &&
                                record.sourceNamespace == source.namespace &&
                                record.sourceCanonicalId == source.canonicalId &&
                                record.targetNamespace == target.namespace &&
                                record.targetCanonicalId == target.canonicalId
                            ) {
                                activationObserved = true
                            }
                        }
                    },
                )
            }
        if (activationObserved) {
            commitEntered.complete(Unit)
            withContext(NonCancellable) { releaseCommit.await() }
        }
        return result
    }
}

private class AliasRetireGateJournal(
    registrations: Map<String, MutatorRegistration<*, String>>,
) : StorageBackedMutationJournal<String>(
        storage = InMemoryMutationJournalStorage(),
        registrations = registrations,
        hydrateOnFirstUse = true,
    ) {
    val retireEntered = CompletableDeferred<Unit>()
    val releaseRetire = CompletableDeferred<Unit>()

    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        retireEntered.complete(Unit)
        releaseRetire.await()
        super.retire(key, mutationId)
    }
}

private class LateAppendGateJournal(
    registrations: Map<String, MutatorRegistration<*, String>>,
) : StorageBackedMutationJournal<String>(
        storage = InMemoryMutationJournalStorage(),
        registrations = registrations,
        hydrateOnFirstUse = true,
    ) {
    val lateAppendEntered = CompletableDeferred<Unit>()
    val releaseLateAppend = CompletableDeferred<Unit>()
    private var gateNextAppend = false

    fun armLateAppend() {
        gateNextAppend = true
    }

    override suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<String>,
    ): String {
        if (gateNextAppend) {
            gateNextAppend = false
            lateAppendEntered.complete(Unit)
            releaseLateAppend.await()
        }
        return super.append(key, entry)
    }
}

private enum class InspectionSplit {
    KEYED,
    GLOBAL,
}

private class SnapshotSwapOnReadJournal(
    registrations: Map<String, MutatorRegistration<*, String>>,
) : StorageBackedMutationJournal<String>(
        storage = InMemoryMutationJournalStorage(),
        registrations = registrations,
        hydrateOnFirstUse = true,
    ) {
    private var split: InspectionSplit? = null
    private lateinit var source: KeyIdentity
    private lateinit var target: KeyIdentity
    private lateinit var retiredMutationId: String

    fun arm(
        split: InspectionSplit,
        source: KeyIdentity,
        target: KeyIdentity,
        retiredMutationId: String,
    ) {
        this.split = split
        this.source = source
        this.target = target
        this.retiredMutationId = retiredMutationId
    }

    override fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<String>> {
        if (split == InspectionSplit.KEYED) swapToActiveSnapshot()
        return super.pendingSnapshot(key)
    }

    override fun identities(): Set<KeyIdentity> {
        val before = super.identities()
        if (split == InspectionSplit.GLOBAL) swapToActiveSnapshot()
        return before
    }

    private fun swapToActiveSnapshot() {
        if (split == null) return
        split = null
        val current = runtimeState.snapshots.value
        val edge = checkNotNull(current.aliases[source])
        val siblings =
            current.entries[source]
                .orEmpty()
                .filterNot { entry -> entry.mutationId == retiredMutationId }
        val targetRows =
            (current.entries[target].orEmpty() + siblings)
                .distinctBy(JournalEntry<String>::mutationId)
                .sortedBy(JournalEntry<String>::durableClientSequence)
        val movedEntries =
            if (targetRows.isEmpty()) {
                current.entries - source - target
            } else {
                current.entries - source + (target to targetRows)
            }
        runtimeState.snapshots.value =
            current.copy(
                entries = movedEntries,
                aliases = current.aliases + (source to edge.copy(state = AliasEdgeState.ACTIVE)),
            )
    }
}

private class InspectionSplitHarness(
    val users: MutationStore<MutationsTestKey, String>,
    val source: MutationsTestKey,
    val siblingId: String,
    val drain: Deferred<Unit>,
)

private suspend fun TestScope.stageInspectionSplit(
    split: InspectionSplit,
): InspectionSplitHarness {
    val mutations = AliasMutationSet()
    val backend = FakeBackend()
    val source = MutationsTestKey("inspection-source-${split.name.lowercase()}")
    val target = MutationsTestKey("inspection-target-${split.name.lowercase()}")
    backend.redirectEcho(source.canonicalId() to target.canonicalId())
    val applyEntered = CompletableDeferred<Unit>()
    val releaseApply = CompletableDeferred<Unit>()
    val handle =
        object : StoreWriteHandle<MutationsTestKey, String> {
            override suspend fun apply(
                key: MutationsTestKey,
                value: String,
            ) {
                applyEntered.complete(Unit)
                releaseApply.await()
            }

            override suspend fun markStale(key: MutationsTestKey) = Unit

            override suspend fun confirmFresh(
                key: MutationsTestKey,
                etag: String?,
            ) = Unit
        }
    val journal = SnapshotSwapOnReadJournal(mutations.registry.registrations)
    val harness =
        aliasHarness(
            registry = mutations.registry,
            backend = backend,
            journal = journal,
            engineWriteHandle = handle,
            nonSuspendingBaseValue = "base",
        )
    val headId = harness.users.mutate(source, mutations.rename, "head")
    val siblingId = harness.users.mutate(source, mutations.append, "+tail")
    val drain =
        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            harness.users.drain(source)
        }
    applyEntered.await()
    journal.arm(split, source.identity(), target.identity(), headId)
    return InspectionSplitHarness(harness.users, source, siblingId, drain)
}

/**
 * Mirrors the [mutationStore] factory wiring — same delegate construction cycle, captured
 * runtime, and narrowed facade — while retaining the engine door these tests must reach
 * (drain-failure carriers, live-key cache, revision subscriptions) plus two staging levers:
 * [engineWriteHandle] substitutes the bound write handle and [nonSuspendingBaseValue] replaces
 * the delegate `LocalOnly` base reader so a drain pass has no suspension point before its
 * accepted-state emission (the cancellation regression's staging). The factory path itself is
 * exercised by the redirect/switch/chain routing tests above.
 */
private fun aliasHarness(
    registry: MutatorRegistry<MutationsTestKey, String>,
    backend: FakeBackend,
    keyResolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    journal: StorageBackedMutationJournal<String> = InMemoryMutationJournal(),
    engineWriteHandle: StoreWriteHandle<MutationsTestKey, String>? = null,
    nonSuspendingBaseValue: String? = null,
): AliasHarness {
    var boundDelegate: Store<MutationsTestKey, String>? = null
    val engine =
        MutationEngine(
            registry = registry,
            server = backend,
            journal = journal,
            keyResolver = keyResolver,
            valueCodecVersion = 1,
            valueCodec = FixtureStringArgsCodec,
            baseReader =
                if (nonSuspendingBaseValue != null) {
                    { nonSuspendingBaseValue }
                } else {
                    { key -> checkNotNull(boundDelegate).localBaseOrNull(key) }
                },
            absentAdoption = { key -> checkNotNull(boundDelegate).clear(key) },
        )
    val delegate =
        store<MutationsTestKey, String> {
            fetcher { backend.load(it) }
            overlay(engine.overlay)
        }
    boundDelegate = delegate
    val runtime = checkNotNull(delegate.runtime())
    engine.bind(engineWriteHandle ?: runtime.writeHandle)
    return AliasHarness(
        engine = engine,
        users = MutationStore(delegate, engine, runtime.keyEvents),
    )
}

private suspend fun Store<MutationsTestKey, String>.localBaseOrNull(
    key: MutationsTestKey,
): String? =
    try {
        get(key, Freshness.LocalOnly)
    } catch (failure: StoreException) {
        if (failure.error is StoreError.Missing) null else throw failure
    }

private class ScriptableWriteHandle : StoreWriteHandle<MutationsTestKey, String> {
    var applyFailure: Throwable? = null
    val applied = mutableListOf<Pair<String, String>>()

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        applyFailure?.let { throw it }
        applied += key.canonicalId() to value
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitOverlayValue(
    expected: String,
): StoreResult.Data<String> {
    while (true) {
        val data = awaitData()
        if (data.value == expected && data.origin == Origin.OVERLAY) return data
    }
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitNonOverlayValue(
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
