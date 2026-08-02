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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
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
 * R1-20/R1-24/R1-09's 021 slice: the same-process canonical alias facade (D15a) and the D14
 * facade conversion-error/liveness contract. Everything here exercises the in-memory preview —
 * normalized full-pair redirects, sequence-merged sibling re-homing, the lost-wakeup-free
 * per-terminal-identity revision signals, and explicit keyed facade routing.
 *
 * The durable model is deliberately absent (022–024). Deferred proofs recorded, not faked:
 * - 022 `MutationJournalContractTest.kt::aliasEdgesAndActivation_roundTripAcrossRestart`
 * - 023 `MutationAckOrchestrationTest.kt::ackAliasActivationRebasesQueuedSourceAndTargetSiblings`
 * - 023 `MutationConflictTest.kt::serverWinsCancellationAfterCommit_stillPublishesOverlayRevision`
 * - 023 `MutationDrainParkingTest.kt::parkingCancellationAfterCommit_stillPublishesOverlayRevisionAndRebasesSuffix`
 * - 023 parks the alias-protocol violations that halt with a normalized `PROTOCOL` carrier here.
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
            // queued siblings from source and target merge by durable client sequence (D15a).
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
                // definition, not stale (ruling: pending UI keys on origin == OVERLAY).
                val optimistic = awaitOverlayValue("draft")
                assertEquals(Origin.OVERLAY, optimistic.origin)
                assertFalse(optimistic.isStale)

                users.drain(provisional)

                // The live provisional stream re-resolves on the alias-revision bump and swaps
                // to delegate.stream(canonical) (D15a): the confirmed canonical frame arrives
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
        // target reuses the pending edge — idempotent, no protocol failure. (In-memory only: the
        // durable ACKED-never-repushed rule is 022/023's; this preview replays the generation.)
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
            // chain to the terminal identity (D15a: chains resolve transitively).
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
        val mutations = AliasMutationSet()
        val backend = FakeBackend()
        var retargetTarget = "real-b1"
        var retryTarget = "real-c1"
        backend.pushBehavior = { key, value ->
            val canonicalId =
                when (key.canonicalId()) {
                    "temp-a" -> "real-a"
                    "real-a" -> "temp-a" // the cycle attempt
                    "temp-b" -> retargetTarget
                    "temp-c" -> retryTarget
                    else -> null
                }
            MutationPresentAck(
                authoritative = value,
                etag = "etag",
                canonicalKey = canonicalId?.let(::MutationsTestKey),
            )
        }
        val handle = ScriptableWriteHandle()
        val journal = InMemoryMutationJournal<String>()
        val engine =
            MutationEngine(
                registry = mutations.registry,
                server = backend,
                journal = journal,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
            )
        engine.bind(handle)

        // --- Cycle: temp-a -> real-a is active; an ack at real-a claiming temp-a must reject.
        val cycleSource = MutationsTestKey("temp-a")
        val cycleTarget = MutationsTestKey("real-a")
        engine.mutate(cycleSource, mutations.rename, "one")
        engine.drain(cycleSource)
        assertEquals(
            cycleTarget.identity(),
            engine.terminalIdentityOf(cycleSource.identity()),
        )
        val appliesBeforeCycle = handle.applied.size
        val cycleIntent = engine.mutate(cycleTarget, mutations.rename, "two")
        engine.drain(cycleTarget)
        assertEquals(appliesBeforeCycle, handle.applied.size)
        assertEquals(
            listOf(cycleIntent),
            engine.pending(cycleTarget).map(PendingIntent::mutationId),
        )
        assertEquals(1, engine.pending(cycleTarget).single().attempt)
        assertEquals(
            cycleTarget.identity(),
            engine.terminalIdentityOf(cycleSource.identity()),
        )

        // --- Retarget: a DIFFERENT intent at an already-aliased source claims a new target.
        val retargetSource = MutationsTestKey("temp-b")
        handle.applyFailure = IllegalStateException("first adoption interrupted")
        val firstRetargetIntent = engine.mutate(retargetSource, mutations.rename, "three")
        assertFailsWith<IllegalStateException> { engine.drain(retargetSource) }
        handle.applyFailure = null
        // Clear the half-adopted head directly (staging only, like the overlay regressions'
        // direct journal use) so the NEXT intent — a new generation/idempotency key — pushes
        // against the pinned pending edge temp-b -> real-b1.
        journal.retire(retargetSource.identity(), firstRetargetIntent)
        retargetTarget = "real-b2"
        val retargetIntent = engine.mutate(retargetSource, mutations.rename, "four")
        val appliesBeforeRetarget = handle.applied.size
        engine.drain(retargetSource)
        assertEquals(appliesBeforeRetarget, handle.applied.size)
        assertEquals(
            listOf(retargetIntent),
            engine.pending(retargetSource).map(PendingIntent::mutationId),
        )

        // --- Retry mismatch: the SAME generation retries and acknowledges a different target.
        val retrySource = MutationsTestKey("temp-c")
        handle.applyFailure = IllegalStateException("retry adoption interrupted")
        val retryIntent = engine.mutate(retrySource, mutations.rename, "five")
        assertFailsWith<IllegalStateException> { engine.drain(retrySource) }
        handle.applyFailure = null
        retryTarget = "real-c2"
        val appliesBeforeRetry = handle.applied.size
        engine.drain(retrySource)
        assertEquals(appliesBeforeRetry, handle.applied.size)
        assertEquals(
            listOf(retryIntent),
            engine.pending(retrySource).map(PendingIntent::mutationId),
        )
        assertEquals(
            retrySource.identity(),
            engine.terminalIdentityOf(retrySource.identity()),
        )

        // All three violations surfaced as normalized PROTOCOL carriers, in order, with no
        // Throwable retained (D3/D15a); 023 owns the durable park these halts preview.
        val failures = engine.drainFailuresForInspection()
        assertEquals(
            listOf(
                ALIAS_FAILURE_DETAIL_CYCLE,
                ALIAS_FAILURE_DETAIL_RETARGET,
                ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH,
            ),
            failures.map(MutationFailure::detail),
        )
        assertTrue(failures.all { failure -> failure.kind == MutationFailureKind.PROTOCOL })
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
                // source key, no completion (D14).
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
            // the thrown cause in the immediate public exception only (D14).
            val fromGet = assertFailsWith<StoreException> { users.get(provisional) }
            assertIs<StoreError.Conversion>(fromGet.error)
            assertSame(boom, fromGet.cause)

            val fromInvalidate = assertFailsWith<StoreException> { users.invalidate(provisional) }
            assertIs<StoreError.Conversion>(fromInvalidate.error)

            // Keyed drain sits in D14's park-not-throw camp: an unresolvable pre-ack terminal
            // records the normalized IDENTITY carrier and returns normally (023 converts these
            // halts into durable parks).
            val failuresBeforeDrain = harness.engine.drainFailuresForInspection().size
            users.drain(provisional)
            val drainFailure = harness.engine.drainFailuresForInspection().last()
            assertEquals(failuresBeforeDrain + 1, harness.engine.drainFailuresForInspection().size)
            assertEquals(MutationFailureKind.IDENTITY, drainFailure.kind)
            assertEquals(DRAIN_FAILURE_DETAIL_KEYED_TERMINAL_UNRESOLVED, drainFailure.detail)

            // Resolver null has no cause (D14).
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

            // mutate resolves BEFORE the append: failure creates no intent anywhere (D14).
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
            // reconstructed, so a dead resolver cannot fail this inspection (D14).
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
                // though the drain caller is about to be cancelled (D15a step 5; Shared
                // invariants' accepted-state handoff).
                assertEquals(
                    canonical.identity(),
                    engine.terminalIdentityOf(provisional.identity()),
                )
                assertTrue(engine.aliasRevision(provisional.identity()).value > 0L)

                cancelledDrain.cancel()
                testScheduler.runCurrent()
                cancelledDrain.join()
                assertTrue(cancelledDrain.isCancelled)

                // Both accepted-state key-change signals survived the cancellation.
                assertTrue(provisionalObserved.isCompleted)
                assertTrue(canonicalObserved.isCompleted)

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
     * R1-09's alias-facing ack-variant rule, standalone: a retry of one generation idempotency
     * key must return the same canonical target or the intent halts as a protocol violation
     * (D15a). The staging mirrors the retry-mismatch arm of
     * [cycleRetargetAndRetryMismatchAreProtocolFailures] with the ack-variant assertions.
     */
    @Test
    fun retryOfGenerationWithDifferentCanonicalTarget_isProtocolFailure() = runTest {
        val mutations = AliasMutationSet()
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
                keyResolver = MutationsTestKeyResolver,
                baseReader = { "base" },
            )
        engine.bind(handle)
        val provisional = MutationsTestKey("temp-1")

        // First attempt: the ack's target is validated and receipted, then adoption fails, so
        // the same generation stays eligible for an exact replay.
        handle.applyFailure = IllegalStateException("adoption interrupted")
        val intent = engine.mutate(provisional, mutations.rename, "draft")
        assertFailsWith<IllegalStateException> { engine.drain(provisional) }
        handle.applyFailure = null

        // The retry of the SAME idempotency key acknowledges a different canonical target.
        canonicalTarget = "real-9"
        val appliesBefore = handle.applied.size
        engine.drain(provisional)

        assertEquals(2, backend.receivedPushes.size)
        assertEquals(
            backend.receivedPushes.first().idempotencyKey,
            backend.receivedPushes.last().idempotencyKey,
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.PROTOCOL, failure.kind)
        assertEquals(ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH, failure.detail)
        assertEquals(appliesBefore, handle.applied.size)
        assertEquals(listOf(intent), engine.pending(provisional).map(PendingIntent::mutationId))
        assertEquals(
            provisional.identity(),
            engine.terminalIdentityOf(provisional.identity()),
        )
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
 * canonical id (D15a); every other key acknowledges with an unchanged identity.
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

/** The ruled public entry point, used where no engine door is needed. */
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
    journal: MutationJournal<String> = InMemoryMutationJournal(),
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

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
