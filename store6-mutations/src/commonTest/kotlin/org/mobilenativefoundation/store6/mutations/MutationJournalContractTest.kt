@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectKind
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord as StoredEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Restart and durable-journal integration contract for the mutation engine. */
class MutationJournalContractTest {
    @Test
    fun journalStorage_isForwardedToEngine() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        val store = openRestartStore(storage, mutations)

        try {
            val mutationId = store.mutate(RestartKey("forwarded", "process-only"), mutations.upsert, "+mine")
            storage.transaction { transaction ->
                val client = assertNotNull(transaction.client("client-0"))
                assertEquals(1L, client.lastAllocatedSequence)
                assertEquals(0L, client.retiredThroughSequence)

                val intent = transaction.intents("client-0").single()
                assertEquals(mutationId, intent.mutationId)
                assertEquals("mutations", intent.namespace)
                assertEquals("forwarded", intent.canonicalId)
                assertEquals("upsert", intent.mutatorId)
                assertEquals(1, intent.mutatorVersion)
                assertContentEquals("+mine".encodeToByteArray(), intent.argsBlob)

                val execution = transaction.executions("client-0").single()
                assertEquals(1L, execution.clientSequence)
                assertEquals(StoredPhase.UNPREPARED, execution.phase)
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun restartHydratesIdentityWithoutPersistingK() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        val firstKey = RestartKey("identity-only", "must-never-be-durable")
        openRestartStore(storage, mutations).use { first ->
            first.mutate(firstKey, mutations.upsert, "+mine")
        }

        val resolver = RecordingRestartResolver()
        val server = RecordingRestartServer(retirementConfirmationCeiling = 0L)
        openRestartStore(storage, mutations, server = server, resolver = resolver).use { reopened ->
            val pending = reopened.pending(RestartKey("identity-only", "second-process"))
            assertEquals(listOf("mutation-1"), pending.map(PendingIntent::mutationId))
            reopened.drain()
        }

        assertEquals(listOf("mutations" to "identity-only"), resolver.requests)
        val push = server.pushes.single()
        assertEquals("identity-only", push.identity.canonicalId)
        assertEquals("resolver-process", push.key.processSentinel)
        assertNotSame(firstKey, push.key)
        storage.transaction { transaction ->
            val intent = transaction.intents("client-0").single()
            assertEquals("mutations", intent.namespace)
            assertEquals("identity-only", intent.canonicalId)
            val durableBlobs =
                buildList {
                    add(intent.argsBlob)
                    transaction.attempts("client-0").forEach { attempt ->
                        attempt.baseBlob?.let(::add)
                        attempt.mineBlob?.let(::add)
                    }
                    transaction.acks("client-0").forEach { ack ->
                        ack.authoritativeBlob?.let(::add)
                    }
                }
            assertTrue(
                durableBlobs.none { blob ->
                    blob.decodeToString().contains(firstKey.processSentinel)
                },
            )
        }
    }

    @Test
    fun immutableAttemptGeneration_roundTripsAcrossRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        val server = RecordingRestartServer(cancelPushes = 2)
        val key = RestartKey("immutable-generation", "first-process")

        openRestartStore(storage, mutations, server = server).use { first ->
            assertEquals("confirmed", first.get(key))
            first.mutate(key, mutations.upsert, "+mine")
            assertFailsWith<CancellationException> { first.drain(key) }
        }
        val firstAttempt = storage.transaction { it.attempts("client-0").single() }.snapshot()
        assertEquals(
            StoredPhase.INFLIGHT,
            storage.transaction { it.executions("client-0").single().phase },
        )

        openRestartStore(
            storage,
            mutations,
            server = server,
            resolver = RecordingRestartResolver(),
        ).use { reopened ->
            assertFailsWith<CancellationException> { reopened.drain() }
        }
        val afterRestart = storage.transaction { it.attempts("client-0").single() }.snapshot()
        val restartedExecution = storage.transaction { it.executions("client-0").single() }

        assertEquals(firstAttempt, afterRestart)
        assertEquals(StoredPhase.INFLIGHT, restartedExecution.phase)
        assertEquals(0, restartedExecution.attempt)
        assertEquals(2, server.pushes.size)
        assertEquals(server.pushes[0].snapshot(), server.pushes[1].snapshot())
    }

    @Test
    fun confirmedRetiredPrefix_roundTripsAcrossRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        val server = RecordingRestartServer(retirementConfirmationCeiling = 1L)
        val firstKey = RestartKey("prefix-one", "first-process")
        val secondKey = RestartKey("prefix-two", "first-process")
        val thirdKey = RestartKey("prefix-three", "first-process")

        openRestartStore(storage, mutations, server = server).use { first ->
            first.mutate(firstKey, mutations.upsert, "+one")
            first.drain(firstKey)
            storage.transaction { transaction ->
                transaction.confirmRetiredThrough("client-0", 1L, 1L)
            }
            first.mutate(secondKey, mutations.upsert, "+two")
            first.mutate(thirdKey, mutations.upsert, "+three")
            first.drain(thirdKey)
        }
        storage.transaction { transaction ->
            val client = assertNotNull(transaction.client("client-0"))
            assertEquals(1L, client.retiredThroughSequence)
            assertEquals(1L, client.serverConfirmedRetiredThroughSequence)
        }

        openRestartStore(
            storage,
            mutations,
            server = server,
            resolver = RecordingRestartResolver(),
        ).use { reopened ->
            reopened.drain(secondKey)
            val fourthKey = RestartKey("prefix-four", "second-process")
            reopened.mutate(fourthKey, mutations.upsert, "+four")
            reopened.drain(fourthKey)
        }

        storage.transaction { transaction ->
            val client = assertNotNull(transaction.client("client-0"))
            assertEquals(4L, client.lastAllocatedSequence)
            assertEquals(4L, client.retiredThroughSequence)
            assertEquals(1L, client.serverConfirmedRetiredThroughSequence)
        }
        val fourthPush = server.pushes.single { push -> push.clientSequence == 4L }
        assertEquals(3L, fourthPush.retiredThroughSequence)
    }

    @Test
    fun inspectionSnapshots_hydrateIdenticallyAcrossRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedEveryExecutionPhase(storage)
        val mutations = restartMutators()

        val before =
            openRestartStore(storage, mutations).use { first ->
                InspectionSnapshot(
                    pending = first.pendingWrites().map(PendingIntent::snapshot),
                    keyed = phaseKeyedSnapshots(first),
                    deadLetters = first.deadLetters().map(DeadLetter::snapshot),
                )
            }
        val after =
            openRestartStore(storage, mutations).use { reopened ->
                InspectionSnapshot(
                    pending = reopened.pendingWrites().map(PendingIntent::snapshot),
                    keyed = phaseKeyedSnapshots(reopened),
                    deadLetters = reopened.deadLetters().map(DeadLetter::snapshot),
                )
            }

        assertEquals(before, after)
        assertEquals(
            listOf(
                MutationPendingState.PENDING,
                MutationPendingState.PENDING,
                MutationPendingState.INFLIGHT,
                MutationPendingState.REFRESHING,
                MutationPendingState.ADOPTING,
                MutationPendingState.APPLYING_EFFECTS,
            ),
            after.pending.map(PendingSnapshot::state),
        )
        assertEquals(listOf("mutation-7"), after.deadLetters.map(DeadLetterSnapshot::mutationId))
        assertFalse(after.pending.any { row -> row.mutationId == "mutation-8" })
    }

    @Test
    fun normalizedFailure_roundTripsWithoutRawCauseBytes() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedParkedFailure(storage)
        val mutations = restartMutators()

        val first =
            openRestartStore(storage, mutations).use { store ->
                store.deadLetters().single().snapshot()
            }
        val second =
            openRestartStore(storage, mutations).use { store ->
                store.deadLetters().single().snapshot()
            }

        assertEquals(first, second)
        assertEquals(MutationFailureKind.CODEC, second.failureKind)
        assertEquals("codec-version", second.failureDetail)
        assertEquals("unsupported durable codec", second.failureMessage)
        assertFalse(second.failureMessage.contains("Throwable"))
        assertFalse(second.failureMessage.contains("\n"))
    }

    @Test
    fun persistenceFailure_preservesLastDurablePhase() = runTest {
        val delegate = InMemoryMutationJournalStorage()
        val storage = ArmableFailingStorage(delegate)
        val mutations = restartMutators()
        val key = RestartKey("persistence-failure", "first-process")

        openRestartStore(storage, mutations).use { first ->
            first.mutate(key, mutations.upsert, "+mine")
            storage.failNextReadyAdvance = true
            assertFailsWith<InjectedPersistenceFailure> { first.drain(key) }
        }
        delegate.transaction { transaction ->
            assertEquals(StoredPhase.UNPREPARED, transaction.executions("client-0").single().phase)
            assertTrue(transaction.attempts("client-0").isEmpty())
        }

        val server = RecordingRestartServer()
        openRestartStore(
            delegate,
            mutations,
            server = server,
            resolver = RecordingRestartResolver(),
        ).use { reopened ->
            assertEquals(MutationPendingState.PENDING, reopened.pendingWrites().single().state)
            reopened.drain()
        }
        assertEquals(1, server.pushes.size)
    }

    @Test
    fun globalDrainEnumeratesDurableIdentitiesAfterRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        openRestartStore(storage, mutations).use { first ->
            first.mutate(RestartKey("enumerated-a", "first-process"), mutations.upsert, "+a")
            first.mutate(RestartKey("enumerated-b", "first-process"), mutations.upsert, "+b")
        }

        val resolver = RecordingRestartResolver()
        val server = RecordingRestartServer()
        openRestartStore(storage, mutations, server = server, resolver = resolver).use { reopened ->
            reopened.drain()
        }

        assertEquals(
            listOf("mutations" to "enumerated-a", "mutations" to "enumerated-b"),
            resolver.requests,
        )
        assertEquals(
            listOf("enumerated-a", "enumerated-b"),
            server.pushes.map { it.key.canonicalId() },
        )
    }

    @Test
    fun valueCodecVersionAndBlobs_roundTripAcrossRestart() = runTest {
        val backing = InMemoryMutationJournalStorage()
        val mutations = restartMutators()
        val writingCodec =
            StrictRecordingStringCodec(
                supportedVersions = setOf(7),
                encodeVersion = 7,
            )
        val server =
            RecordingRestartServer(
                cancelPushes = 1,
                retirementConfirmationCeiling = 0L,
            )
        val key = RestartKey("value-codec", "first-process")

        openRestartStore(
            backing,
            mutations,
            server = server,
            valueCodecVersion = 7,
            valueCodec = writingCodec,
        ).use { first ->
            assertEquals("confirmed", first.get(key))
            first.mutate(key, mutations.upsert, "+mine")
            assertFailsWith<CancellationException> { first.drain(key) }
        }
        writingCodec.encodedBuffers.forEach { bytes -> bytes.fill(0) }
        val beforeRestart = backing.transaction { it.attempts("client-0").single().snapshot() }
        assertEquals(7, beforeRestart.codecVersion)
        assertEquals("store6-codec-7:confirmed".encodeToByteArray().toList(), beforeRestart.baseBlob)
        assertEquals("store6-codec-7:confirmed+mine".encodeToByteArray().toList(), beforeRestart.mineBlob)

        val failPointStorage = FailPointJournalStorage(backing)
        failPointStorage.armKillAfterCommit(JournalFailPointBoundary.ACK_RECEIPT)
        val replayCodec =
            StrictRecordingStringCodec(
                supportedVersions = setOf(7, 9),
                encodeVersion = 9,
            )
        openRestartStore(
            failPointStorage,
            mutations,
            server = server,
            resolver = RecordingRestartResolver(),
            valueCodecVersion = 9,
            valueCodec = replayCodec,
        ).use { reopened ->
            assertEquals(listOf("mutation-1"), reopened.pendingWrites().map(PendingIntent::mutationId))
            assertEquals(listOf(7, 7), replayCodec.decodeVersions)
            val processDeath =
                assertFailsWith<FailPointProcessDeathException> { reopened.drain() }
            assertTrue(processDeath.committed)
        }

        val afterAckCommit =
            backing.transaction { transaction ->
                Triple(
                    transaction.attempts("client-0").single().snapshot(),
                    transaction.acks("client-0").single(),
                    transaction.executions("client-0").single(),
                )
            }
        assertEquals(beforeRestart, afterAckCommit.first)
        assertEquals(7, afterAckCommit.first.codecVersion)
        assertEquals(9, afterAckCommit.second.valueCodecVersion)
        assertContentEquals(
            "store6-codec-9:confirmed+mine".encodeToByteArray(),
            afterAckCommit.second.authoritativeBlob,
        )
        assertEquals(StoredPhase.ACKED, afterAckCommit.third.phase)
        assertEquals(2, server.pushes.size)
        val cancelledPush = server.pushes.first()
        val replayedPush = server.pushes.last()
        assertEquals(7, cancelledPush.valueCodecVersion)
        assertEquals(7, replayedPush.valueCodecVersion)
        assertEquals(1, replayedPush.generation)
        assertEquals(cancelledPush.generation, replayedPush.generation)
        assertEquals(cancelledPush.idempotencyKey, replayedPush.idempotencyKey)
        assertEquals(cancelledPush.identity.namespace, replayedPush.identity.namespace)
        assertEquals(cancelledPush.identity.canonicalId, replayedPush.identity.canonicalId)
        assertEquals(
            assertIs<MutationPresence.Present<String>>(cancelledPush.base).value,
            assertIs<MutationPresence.Present<String>>(replayedPush.base).value,
        )
        assertEquals(
            assertIs<MutationPresence.Present<String>>(cancelledPush.mine).value,
            assertIs<MutationPresence.Present<String>>(replayedPush.mine).value,
        )
        assertEquals(
            cancelledPush.baseMeta?.writtenAtEpochMillis,
            replayedPush.baseMeta?.writtenAtEpochMillis,
        )
        assertEquals(cancelledPush.baseMeta?.etag, replayedPush.baseMeta?.etag)
        assertEquals("confirmed", assertIs<MutationPresence.Present<String>>(replayedPush.base).value)
        assertEquals("confirmed+mine", assertIs<MutationPresence.Present<String>>(replayedPush.mine).value)

        replayCodec.encodedBuffers.forEach { bytes -> bytes.fill(0) }
        replayCodec.decodeInputs.forEach { bytes -> bytes.fill(0) }
        val afterCodecBufferMutation =
            backing.transaction { transaction ->
                Triple(
                    transaction.attempts("client-0").single().snapshot(),
                    transaction.acks("client-0").single(),
                    transaction.executions("client-0").single(),
                )
            }
        assertEquals(beforeRestart, afterCodecBufferMutation.first)
        assertEquals(9, afterCodecBufferMutation.second.valueCodecVersion)
        assertContentEquals(
            "store6-codec-9:confirmed+mine".encodeToByteArray(),
            afterCodecBufferMutation.second.authoritativeBlob,
        )
        assertEquals(StoredPhase.ACKED, afterCodecBufferMutation.third.phase)

        val adoptingCodec =
            StrictRecordingStringCodec(
                supportedVersions = setOf(7, 9),
                encodeVersion = 9,
            )
        val pushesBeforeAdoption = server.pushes.size
        openRestartStore(
            backing,
            mutations,
            server = server,
            resolver = RecordingRestartResolver(),
            valueCodecVersion = 9,
            valueCodec = adoptingCodec,
        ).use { reopened ->
            reopened.drain()
            assertTrue(reopened.pendingWrites().isEmpty())
        }

        assertEquals(pushesBeforeAdoption, server.pushes.size)
        assertTrue(9 in adoptingCodec.decodeVersions)
    }

    @Test
    fun unknownPreAckValueCodecVersion_hydratesAsParkableCodecFailure() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedCodecExecution(storage, StoredPhase.READY, valueCodecVersion = 99)
        val server = RecordingRestartServer()
        val codec = StrictRecordingStringCodec(supportedVersions = setOf(1))

        openRestartStore(
            storage,
            restartMutators(),
            server = server,
            valueCodec = codec,
        ).use { reopened ->
            assertEquals(MutationPendingState.PENDING, reopened.pendingWrites().single().state)
            reopened.drain()
            assertTrue(reopened.pendingWrites().isEmpty())
            val deadLetter = reopened.deadLetters().single()
            assertEquals("mutation-1", deadLetter.mutationId)
            assertEquals(1, deadLetter.generation)
            assertEquals(0, deadLetter.attempts)
            assertEquals(MutationFailureKind.CODEC, deadLetter.failure.kind)
            assertEquals("value-codec-pre-ack", deadLetter.failure.detail)
        }

        val failure = storage.transaction { it.failures("client-0").single() }
        assertEquals(MutationFailureKind.CODEC, failure.kind)
        assertEquals(1, failure.generation)
        assertEquals("value-codec-pre-ack", failure.detail)
        assertFalse(failure.message.contains("IllegalArgumentException"))
        assertFalse(failure.message.contains('\n'))
        val execution = storage.transaction { it.executions("client-0").single() }
        assertEquals(StoredPhase.PARKED, execution.phase)
        assertEquals(failure.failureId, execution.activeFailureId)
        assertTrue(server.pushes.isEmpty())
    }

    @Test
    fun unknownAckValueCodecVersion_hydratesAsRetryableAckedCodecFailure() = runTest {
        val storage = InMemoryMutationJournalStorage()
        seedCodecExecution(storage, StoredPhase.ACKED, valueCodecVersion = 99)
        val server = RecordingRestartServer()
        val codec = StrictRecordingStringCodec(supportedVersions = setOf(1))

        openRestartStore(
            storage,
            restartMutators(),
            server = server,
            valueCodec = codec,
        ).use { reopened ->
            assertEquals(MutationPendingState.ADOPTING, reopened.pendingWrites().single().state)
            reopened.drain()
            assertTrue(reopened.deadLetters().isEmpty())
        }

        val failures = storage.transaction { it.failures("client-0") }
        val failure = failures.single()
        assertEquals(MutationFailureKind.CODEC, failure.kind)
        assertEquals(1, failure.generation)
        assertEquals("value-codec-acked", failure.detail)
        assertFalse(failure.message.contains("IllegalArgumentException"))
        assertFalse(failure.message.contains('\n'))
        val execution = storage.transaction { it.executions("client-0").single() }
        assertEquals(StoredPhase.ACKED, execution.phase)
        assertNull(execution.activeFailureId)
        assertTrue(server.pushes.isEmpty())
    }

    @Test
    fun argsCodecVersionAndBlob_roundTripAcrossRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val writingCodec = StrictRecordingStringCodec(supportedVersions = setOf(3))
        val firstMutations = restartMutators(argsVersion = 3, argsCodec = writingCodec)
        val key = RestartKey("args-codec", "first-process")

        openRestartStore(storage, firstMutations).use { first ->
            first.mutate(key, firstMutations.upsert, "+args")
        }
        writingCodec.encodedBuffers.forEach { bytes -> bytes.fill(0) }
        val beforeRestart = storage.transaction { it.intents("client-0").single() }
        assertEquals(3, beforeRestart.mutatorVersion)
        assertContentEquals("+args".encodeToByteArray(), beforeRestart.argsBlob)

        val readingCodec = StrictRecordingStringCodec(supportedVersions = setOf(3))
        val reopenedMutations = restartMutators(argsVersion = 4, argsCodec = readingCodec)
        val server = RecordingRestartServer(retirementConfirmationCeiling = 0L)
        openRestartStore(storage, reopenedMutations, server = server).use { reopened ->
            assertEquals(listOf("mutation-1"), reopened.pendingWrites().map(PendingIntent::mutationId))
            reopened.drain(RestartKey("args-codec", "second-process"))
        }

        assertEquals(listOf(3), readingCodec.decodeVersions)
        assertEquals("+args", (server.pushes.single().mine as MutationPresence.Present).value)
        readingCodec.decodeInputs.single().fill(0)
        assertContentEquals(
            "+args".encodeToByteArray(),
            storage.transaction { it.intents("client-0").single().argsBlob },
        )
    }

    @Test
    fun callerMutatedArgs_doNotChangeAcceptedProjectionOrRestartReplay() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = mutableArgsMutators()
        val key = RestartKey("mutable-args", "first-process")
        val args = MutableSuffix("+accepted")

        val first = openMutableArgsEngine(storage, mutations)
        first.ensureHydrated()
        first.mutate(key, mutations.upsert, args)
        assertEquals("confirmed+accepted", first.overlay.apply(key, "confirmed"))

        args.value = "+caller-change"
        assertEquals("confirmed+accepted", first.overlay.apply(key, "confirmed"))

        val reopened = openMutableArgsEngine(storage, mutations)
        reopened.ensureHydrated()
        assertEquals(
            "confirmed+accepted",
            reopened.overlay.apply(RestartKey("mutable-args", "second-process"), "confirmed"),
        )
    }

    @Test
    fun unknownMutatorOrArgsVersion_hydratesAsNormalizedCodecFailure() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 2L)
            seedUnpreparedIntent(
                transaction,
                sequence = 1L,
                mutationId = "mutation-1",
                canonicalId = "missing-mutator",
                mutatorId = "not-registered",
                mutatorVersion = 1,
                argsBlob = "+one".encodeToByteArray(),
            )
            seedUnpreparedIntent(
                transaction,
                sequence = 2L,
                mutationId = "mutation-2",
                canonicalId = "unknown-args-version",
                mutatorId = "upsert",
                mutatorVersion = 99,
                argsBlob = "+two".encodeToByteArray(),
            )
        }
        val server = RecordingRestartServer()
        val strictArgs = StrictRecordingStringCodec(supportedVersions = setOf(1))

        openRestartStore(
            storage,
            restartMutators(argsCodec = strictArgs),
            server = server,
        ).use { reopened ->
            assertEquals(
                listOf("mutation-1", "mutation-2"),
                reopened.pendingWrites().map(PendingIntent::mutationId),
            )
            reopened.drain()
            assertTrue(reopened.pendingWrites().isEmpty())
            val deadLetters = reopened.deadLetters()
            assertEquals(2, deadLetters.size)
            assertEquals(
                mapOf(
                    "mutation-1" to "mutator-missing",
                    "mutation-2" to "args-codec",
                ),
                deadLetters.associate { deadLetter ->
                    deadLetter.mutationId to deadLetter.failure.detail
                },
            )
            assertTrue(
                deadLetters.all { deadLetter ->
                    deadLetter.generation == 0 &&
                        deadLetter.attempts == 0 &&
                        deadLetter.failure.kind == MutationFailureKind.CODEC
                },
            )
        }

        val failures = storage.transaction { it.failures("client-0") }
        assertEquals(2, failures.size)
        assertTrue(failures.all { failure -> failure.kind == MutationFailureKind.CODEC })
        assertEquals(setOf("mutator-missing", "args-codec"), failures.map { it.detail }.toSet())
        assertTrue(failures.none { failure -> failure.message.contains("Throwable") })
        assertTrue(failures.none { failure -> failure.message.contains('\n') })
        val failuresBySequence = failures.associateBy { failure -> failure.clientSequence }
        val executions = storage.transaction { it.executions("client-0") }
        assertTrue(
            executions.all { execution ->
                execution.phase == StoredPhase.PARKED &&
                    execution.currentGeneration == 0 &&
                    execution.activeFailureId ==
                        failuresBySequence.getValue(execution.clientSequence).failureId
            },
        )
        assertEquals(listOf(0, 0), failures.map { it.generation })
        assertTrue(server.pushes.isEmpty())

        val acknowledgedStorage = InMemoryMutationJournalStorage()
        seedCodecExecution(
            acknowledgedStorage,
            StoredPhase.ACKED,
            valueCodecVersion = 1,
            mutatorId = "not-registered-after-ack",
        )
        val acknowledgedServer = RecordingRestartServer(retirementConfirmationCeiling = 0L)
        openRestartStore(
            acknowledgedStorage,
            restartMutators(),
            server = acknowledgedServer,
        ).use { reopened ->
            assertEquals(MutationPendingState.ADOPTING, reopened.pendingWrites().single().state)
            reopened.drain()
        }
        assertTrue(acknowledgedServer.pushes.isEmpty())
        assertTrue(acknowledgedStorage.transaction { it.failures("client-0") }.isEmpty())
        assertEquals(
            StoredPhase.RETIRED,
            acknowledgedStorage.transaction { it.executions("client-0").single().phase },
        )
    }

    @Test
    fun deleteArgs_rejectsUnknownVersionOrNonEmptyBlobAsCodec() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 3L)
            seedUnpreparedIntent(
                transaction,
                sequence = 1L,
                mutationId = "mutation-1",
                canonicalId = "delete-version",
                mutatorId = "delete",
                mutatorVersion = 2,
                argsBlob = ByteArray(0),
            )
            seedUnpreparedIntent(
                transaction,
                sequence = 2L,
                mutationId = "mutation-2",
                canonicalId = "delete-bytes",
                mutatorId = "delete",
                mutatorVersion = 1,
                argsBlob = byteArrayOf(1),
            )
            seedUnpreparedIntent(
                transaction,
                sequence = 3L,
                mutationId = "mutation-3",
                canonicalId = "delete-valid-control",
                mutatorId = "delete",
                mutatorVersion = 1,
                argsBlob = ByteArray(0),
            )
        }
        val server = RecordingRestartServer()

        openRestartStore(storage, restartMutators(), server = server).use { reopened ->
            assertEquals(3, reopened.pendingWrites().size)
            reopened.drain()
            assertTrue(reopened.pendingWrites().isEmpty())
            val deadLetters = reopened.deadLetters()
            assertEquals(2, deadLetters.size)
            assertEquals(
                mapOf(
                    "mutation-1" to "args-codec",
                    "mutation-2" to "args-codec",
                ),
                deadLetters.associate { deadLetter ->
                    deadLetter.mutationId to deadLetter.failure.detail
                },
            )
            assertTrue(
                deadLetters.all { deadLetter ->
                    deadLetter.generation == 0 &&
                        deadLetter.attempts == 0 &&
                        deadLetter.failure.kind == MutationFailureKind.CODEC
                },
            )
        }

        val failures = storage.transaction { it.failures("client-0") }
        assertEquals(2, failures.size)
        assertTrue(failures.all { failure -> failure.kind == MutationFailureKind.CODEC })
        assertTrue(failures.all { failure -> failure.detail == "args-codec" })
        assertTrue(failures.all { failure -> failure.generation == 0 })
        assertTrue(failures.any { failure -> failure.message.contains("fixed at version 1") })
        assertTrue(failures.any { failure -> failure.message.contains("zero bytes") })
        val push = server.pushes.single()
        assertEquals(3L, push.clientSequence)
        assertEquals(MutationPresence.Absent, push.mine)
        val failuresBySequence = failures.associateBy { failure -> failure.clientSequence }
        val executions = storage.transaction { it.executions("client-0") }
        assertTrue(
            executions.take(2).all { execution ->
                execution.phase == StoredPhase.PARKED &&
                    execution.currentGeneration == 0 &&
                    execution.activeFailureId ==
                        failuresBySequence.getValue(execution.clientSequence).failureId
            },
        )
        assertEquals(StoredPhase.RETIRED, executions.last().phase)
    }

    @Test
    fun effectTargetsAndDispositions_roundTripAcrossRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val clock = TestWallClock(startEpochMillis = 1_000L)
        val mutations =
            restartMutators(
                stales = { _, _ ->
                    StaleSet(
                        keys =
                            setOf(
                                RestartKey("z", "effect"),
                                RestartKey("a", "effect-one"),
                                RestartKey("a", "effect-duplicate"),
                            ),
                        namespaces =
                            setOf(
                                StoreNamespace("zeta"),
                                StoreNamespace("alpha"),
                                StoreNamespace("alpha"),
                            ),
                    )
                },
            )
        var effectsAtPush: List<EffectSnapshot> = emptyList()
        val server =
            RecordingRestartServer(
                beforePush = {
                    effectsAtPush = storage.transaction { it.effects("client-0").map(StoredEffectRecord::snapshot) }
                },
            )
        val effectTargetStop = IllegalStateException("effect target unavailable")
        val effectResolver =
            MutationKeyResolver<RestartKey> { identity ->
                if (identity.canonicalId == "a" || identity.canonicalId == "z") {
                    throw effectTargetStop
                }
                RestartKey(identity.canonicalId, "resolver-process")
            }
        fun openEffectStore(): MutationStore<RestartKey, String> =
            mutationStore(
                registry = mutations.registry,
                server = server,
                keyResolver = effectResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { "confirmed" }
                journalStorage(storage)
                wallClock(clock)
                bookkeeper(
                    object :
                        org.mobilenativefoundation.store6.core.seam.Bookkeeper by
                        MutationBookkeeper() {
                        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
                            throw effectTargetStop
                        }
                    },
                )
            }
        suspend fun drainAllowingEffectTargetStop(drain: suspend () -> Unit) {
            try {
                drain()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val configuredStoreFailure =
                    failure is org.mobilenativefoundation.store6.core.StoreException &&
                        failure.cause === effectTargetStop
                if (failure !== effectTargetStop && !configuredStoreFailure) {
                    throw failure
                }
            }
        }

        openEffectStore().use { first ->
            first.mutate(RestartKey("effect-source", "first-process"), mutations.upsert, "+mine")
            drainAllowingEffectTargetStop {
                first.drain(RestartKey("effect-source", "first-process"))
            }
            assertEquals(
                MutationPendingState.APPLYING_EFFECTS,
                first.pendingWrites().single().state,
            )
        }

        val expected =
            listOf(
                EffectSnapshot(0, MutationEffectKind.NAMESPACE, "alpha", null, 1_000L),
                EffectSnapshot(1, MutationEffectKind.NAMESPACE, "zeta", null, 1_000L),
                EffectSnapshot(2, MutationEffectKind.KEY, "mutations", "a", 1_000L),
                EffectSnapshot(3, MutationEffectKind.KEY, "mutations", "z", 1_000L),
            )
        assertEquals(expected, effectsAtPush)
        assertEquals(expected, storage.transaction { it.effects("client-0").map(StoredEffectRecord::snapshot) })
        assertEquals(
            StoredPhase.EFFECTS_PENDING,
            storage.transaction { it.executions("client-0").single().phase },
        )

        openEffectStore().use { reopened ->
            assertEquals(
                MutationPendingState.APPLYING_EFFECTS,
                reopened.pendingWrites().single().state,
            )
            assertEquals(
                expected,
                reopened.durableEffectsForInspection("mutation-1").map(StoredEffectRecord::snapshot),
            )
            drainAllowingEffectTargetStop { reopened.drain() }
        }
        assertEquals(1, server.pushes.size)
        assertEquals(expected, storage.transaction { it.effects("client-0").map(StoredEffectRecord::snapshot) })
    }

    @Test
    fun aliasEdgesAndActivation_roundTripAcrossRestart() = runTest {
        val delegate = InMemoryMutationJournalStorage()
        delegate.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 0L)
            insertActiveAlias(
                transaction,
                source = "canonical",
                target = "terminal",
                creatorClientId = "remote-client",
                creatorSequence = 1L,
            )
        }
        val storage = ArmablePhaseFailingStorage(delegate)
        val mutations = restartMutators()
        val source = RestartKey("source", "first-process")
        val canonical = RestartKey("canonical", "server-process")
        val server =
            RecordingRestartServer(
                canonicalTarget = canonical,
                retirementConfirmationCeiling = 0L,
            )

        openRestartStore(storage, mutations, server = server).use { first ->
            first.mutate(source, mutations.upsert, "+mine")
            storage.failNextPhase = StoredPhase.RETIRED
            assertFailsWith<InjectedPersistenceFailure> { first.drain(source) }
        }
        val afterFailure = delegate.transaction { it.aliases().map(MutationKeyAliasRecord::snapshot) }
        assertEquals(MutationAliasState.PENDING, afterFailure.single { it.sourceId == "source" }.state)
        assertEquals(MutationAliasState.ACTIVE, afterFailure.single { it.sourceId == "canonical" }.state)
        // Adoption already committed its ACKED -> EFFECTS_PENDING boundary; the injected
        // RETIRED write failed inside the separate finalization transaction.
        assertEquals(
            StoredPhase.EFFECTS_PENDING,
            delegate.transaction { it.executions("client-0").single().phase },
        )
        assertEquals(0L, assertNotNull(delegate.transaction { it.client("client-0") }).retiredThroughSequence)

        val resumeResolver = RecordingRestartResolver()
        openRestartStore(
            storage,
            mutations,
            server = server,
            resolver = resumeResolver,
        ).use { reopened ->
            reopened.drain()
        }
        assertEquals(1, server.pushes.size)
        val afterResume = delegate.transaction { it.aliases().map(MutationKeyAliasRecord::snapshot) }
        assertEquals(MutationAliasState.ACTIVE, afterResume.single { it.sourceId == "source" }.state)
        assertEquals(StoredPhase.RETIRED, delegate.transaction { it.executions("client-0").single().phase })
        assertEquals(1L, assertNotNull(delegate.transaction { it.client("client-0") }).retiredThroughSequence)

        val terminalResolver = RecordingRestartResolver()
        openRestartStore(
            delegate,
            mutations,
            resolver = terminalResolver,
        ).use { reopened ->
            assertEquals("confirmed", reopened.get(RestartKey("source", "third-process")))
        }
        assertEquals(listOf("mutations" to "terminal"), terminalResolver.requests)
    }

    @Test
    fun ackedChainedAliasResume_adoptsAtTerminalIdentityAfterRestart() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 1L)
            seedUnpreparedIntent(
                transaction = transaction,
                sequence = 1L,
                mutationId = "mutation-1",
                canonicalId = "source",
                mutatorId = "upsert",
                mutatorVersion = 1,
                argsBlob = "+mine".encodeToByteArray(),
            )
            transaction.insertAttempt(
                MutationAttemptRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    generation = 1,
                    effectiveNamespace = "mutations",
                    effectiveCanonicalId = "source",
                    valueCodecVersion = 1,
                    basePresence = MutationPresenceState.PRESENT,
                    baseBlob = "confirmed".encodeToByteArray(),
                    minePresence = MutationPresenceState.PRESENT,
                    mineBlob = "confirmed+mine".encodeToByteArray(),
                    preconditionMetaPresent = false,
                    preconditionWrittenAt = null,
                    preconditionEtag = null,
                    advertisedRetiredThroughSequence = 0L,
                    generationIdempotencyKey = "client-0:1:g1",
                    preparedAt = 200L,
                    conflictMetaPresent = null,
                    conflictWrittenAt = null,
                    conflictEtag = null,
                    conflictReceivedAt = null,
                ),
            )
            val ready =
                MutationExecutionRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    phase = StoredPhase.READY,
                    currentGeneration = 1,
                    attempt = 0,
                    lastAttemptAt = null,
                    activeFailureId = null,
                    retiredAt = null,
                )
            transaction.advanceExecution(ready)
            transaction.advanceExecution(ready.copyPhase(StoredPhase.INFLIGHT))
            transaction.insertAck(
                MutationAckRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    generation = 1,
                    authoritativePresence = MutationPresenceState.PRESENT,
                    authoritativeBlob = "authoritative-chain".encodeToByteArray(),
                    valueCodecVersion = 1,
                    etag = "etag-chain",
                    canonicalTargetNamespace = "mutations",
                    canonicalTargetId = "canonical",
                    receivedAt = 300L,
                ),
            )
            transaction.insertAlias(
                MutationKeyAliasRecord(
                    sourceNamespace = "mutations",
                    sourceCanonicalId = "source",
                    targetNamespace = "mutations",
                    targetCanonicalId = "canonical",
                    state = MutationAliasState.PENDING,
                    createdByClientId = "client-0",
                    createdBySequence = 1L,
                    createdAt = 300L,
                    activatedAt = null,
                ),
            )
            transaction.advanceExecution(
                MutationExecutionRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    phase = StoredPhase.ACKED,
                    currentGeneration = 1,
                    attempt = 1,
                    lastAttemptAt = 300L,
                    activeFailureId = null,
                    retiredAt = null,
                ),
            )
            insertActiveAlias(
                transaction = transaction,
                source = "canonical",
                target = "terminal",
                creatorClientId = "remote-client",
                creatorSequence = 1L,
            )
        }

        val mutations = restartMutators()
        val server = RecordingRestartServer()
        val resolver = RecordingRestartResolver()
        val handle = RecordingRestartWriteHandle()
        val engine =
            MutationEngine(
                registry = mutations.registry,
                server = server,
                journal =
                    StorageBackedMutationJournal(
                        storage = storage,
                        registrations = mutations.registry.registrations,
                        clientId = "client-0",
                        hydrateOnFirstUse = true,
                    ),
                keyResolver = resolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
                clientId = "client-0",
            )
        engine.bind(handle)

        engine.drain(RestartKey("source", "restart"))

        assertTrue(server.pushes.isEmpty())
        assertEquals(listOf("terminal" to "authoritative-chain"), handle.applied)
        assertEquals(
            listOf<Pair<String, String?>>("terminal" to "etag-chain"),
            handle.confirmed,
        )
        assertEquals(listOf("mutations" to "terminal"), resolver.requests)
    }

    @Test
    fun activeAliasSourceSibling_rehomesDuringRestartHydration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 1L)
            seedUnpreparedIntent(
                transaction = transaction,
                sequence = 1L,
                mutationId = "mutation-1",
                canonicalId = "source",
                mutatorId = "upsert",
                mutatorVersion = 1,
                argsBlob = "+sibling".encodeToByteArray(),
            )
            insertActiveAlias(
                transaction = transaction,
                source = "source",
                target = "target",
                creatorClientId = "remote-client",
                creatorSequence = 1L,
            )
        }
        val mutations = restartMutators()

        openRestartStore(storage, mutations).use { reopened ->
            val pending = reopened.pending(RestartKey("source", "restart"))
            assertEquals(listOf("mutation-1"), pending.map(PendingIntent::mutationId))
            assertEquals("target", pending.single().canonicalId)
            assertEquals(
                listOf("target"),
                reopened.pendingWrites().map(PendingIntent::canonicalId),
            )
        }
    }

    @Test
    fun deletePresentDeletePersistsDistinctTombstoneGenerations() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 3L)
            seedAcknowledgedIntent(transaction, "client-0", 1L, MutationPresenceState.ABSENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 2L, MutationPresenceState.PRESENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 3L, MutationPresenceState.ABSENT)
            insertActiveTombstone(transaction, "entity", "client-0", 1L, 10L, 11L)
            supersedeTombstone(transaction, "entity", "client-0", 1L, 10L, 11L, "client-0", 2L, 20L)
            insertActiveTombstone(transaction, "entity", "client-0", 3L, 30L, 31L)
        }

        val rows = hydrateAndReadTombstones(storage, "entity")
        assertEquals(2, rows.size)
        assertEquals(
            TombstoneSnapshot(
                1L,
                MutationTombstoneState.SUPERSEDED,
                10L,
                11L,
                "client-0",
                2L,
                20L,
            ),
            rows[0],
        )
        assertEquals(
            TombstoneSnapshot(3L, MutationTombstoneState.ACTIVE, 30L, 31L, null, null, null),
            rows[1],
        )
    }

    @Test
    fun tombstonesAllowAtMostOnePendingAndOneActiveGeneration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 3L)
            seedAcknowledgedIntent(transaction, "client-0", 1L, MutationPresenceState.ABSENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 2L, MutationPresenceState.ABSENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 3L, MutationPresenceState.ABSENT)
            insertActiveTombstone(transaction, "entity", "client-0", 1L, 10L, 11L)
            transaction.insertTombstone(
                tombstone("entity", "client-0", 2L, MutationTombstoneState.PENDING, 20L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertTombstone(
                    tombstone("entity", "client-0", 3L, MutationTombstoneState.PENDING, 30L),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceTombstone(
                    tombstone(
                        "entity",
                        "client-0",
                        2L,
                        MutationTombstoneState.ACTIVE,
                        20L,
                        activatedAt = 21L,
                    ),
                )
            }
        }

        val rows = hydrateAndReadTombstones(storage, "entity")
        assertEquals(listOf(MutationTombstoneState.ACTIVE, MutationTombstoneState.PENDING), rows.map { it.state })
        assertEquals(listOf(1L, 2L), rows.map { it.creatorSequence })
    }

    @Test
    fun newAbsentActivationSupersedesPriorActiveGeneration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 2L)
            seedAcknowledgedIntent(transaction, "client-0", 1L, MutationPresenceState.ABSENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 2L, MutationPresenceState.ABSENT)
            insertActiveTombstone(transaction, "entity", "client-0", 1L, 10L, 11L)
            transaction.insertTombstone(
                tombstone("entity", "client-0", 2L, MutationTombstoneState.PENDING, 20L),
            )
            supersedeTombstone(transaction, "entity", "client-0", 1L, 10L, 11L, "client-0", 2L, 21L)
            transaction.advanceTombstone(
                tombstone(
                    "entity",
                    "client-0",
                    2L,
                    MutationTombstoneState.ACTIVE,
                    20L,
                    activatedAt = 21L,
                ),
            )
        }

        val rows = hydrateAndReadTombstones(storage, "entity")
        assertEquals(MutationTombstoneState.SUPERSEDED, rows[0].state)
        assertEquals(2L, rows[0].supersededBySequence)
        assertEquals(MutationTombstoneState.ACTIVE, rows[1].state)
        assertEquals(21L, rows[1].activatedAt)
    }

    @Test
    fun laterPresentSupersedesActiveTombstone() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedClient(transaction, lastAllocatedSequence = 2L)
            seedAcknowledgedIntent(transaction, "client-0", 1L, MutationPresenceState.ABSENT, retire = true)
            seedAcknowledgedIntent(transaction, "client-0", 2L, MutationPresenceState.PRESENT)
            insertActiveTombstone(transaction, "entity", "client-0", 1L, 10L, 11L)
            supersedeTombstone(transaction, "entity", "client-0", 1L, 10L, 11L, "client-0", 2L, 20L)
        }

        val rows = hydrateAndReadTombstones(storage, "entity")
        val row = rows.single()
        assertEquals(MutationTombstoneState.SUPERSEDED, row.state)
        assertEquals(2L, row.supersededBySequence)
        assertTrue(rows.none { it.state == MutationTombstoneState.ACTIVE })
        assertTrue(rows.none { it.state == MutationTombstoneState.PENDING })
    }

    @Test
    fun supersededByMayReferenceAnyLaterAuthoritativeIntent() = runTest {
        val storage = InMemoryMutationJournalStorage()
        storage.transaction { transaction ->
            seedNamedClient(transaction, "client-0", 0L)
            seedNamedClient(transaction, "client-a", 5L)
            seedNamedClient(transaction, "client-b", 1L)
            seedAcknowledgedIntent(transaction, "client-a", 5L, MutationPresenceState.ABSENT)
            seedAcknowledgedIntent(transaction, "client-b", 1L, MutationPresenceState.PRESENT)
            insertActiveTombstone(transaction, "entity", "client-a", 5L, 100L, 110L)
            supersedeTombstone(transaction, "entity", "client-a", 5L, 100L, 110L, "client-b", 1L, 200L)
        }

        val row = hydrateAndReadTombstones(storage, "entity").single()
        assertEquals(MutationTombstoneState.SUPERSEDED, row.state)
        assertEquals("client-b", row.supersededByClientId)
        assertEquals(1L, row.supersededBySequence)
        assertTrue(checkNotNull(row.supersededBySequence) < row.creatorSequence)

        val sameClientLowerSuccessor =
            tombstone(
                canonicalId = "structural-only",
                creatorClientId = "client-a",
                creatorSequence = 5L,
                state = MutationTombstoneState.SUPERSEDED,
                createdAt = 100L,
                activatedAt = 110L,
                supersededByClientId = "client-a",
                supersededBySequence = 1L,
                supersededAt = 200L,
            )
        assertEquals("client-a", sameClientLowerSuccessor.supersededByClientId)
        assertEquals(1L, sameClientLowerSuccessor.supersededBySequence)
        assertEquals(110L, sameClientLowerSuccessor.activatedAt)
        assertTrue(
            checkNotNull(sameClientLowerSuccessor.supersededBySequence) <
                sameClientLowerSuccessor.createdBySequence,
        )
    }
}

private class RestartKey(
    private val id: String,
    val processSentinel: String,
    override val namespace: StoreNamespace = StoreNamespace("mutations"),
) : StoreKey {
    override fun canonicalId(): String = id
}

private class RestartMutators(
    val registry: MutatorRegistry<RestartKey, String>,
    val upsert: MutatorRef<RestartKey, String, String>,
    val delete: MutatorRef<RestartKey, String, Unit>,
)

private class MutableSuffix(
    var value: String,
)

private class MutableArgsMutators(
    val registry: MutatorRegistry<RestartKey, String>,
    val upsert: MutatorRef<RestartKey, String, MutableSuffix>,
)

private object MutableSuffixCodec : MutationCodec<MutableSuffix> {
    override fun encode(value: MutableSuffix): ByteArray = value.value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): MutableSuffix = MutableSuffix(bytes.decodeToString())
}

private fun mutableArgsMutators(): MutableArgsMutators {
    lateinit var upsert: MutatorRef<RestartKey, String, MutableSuffix>
    val registry =
        mutatorRegistry<RestartKey, String> {
            upsert =
                upsert(
                    id = "mutable-upsert",
                    version = 1,
                    codec = MutableSuffixCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    val current = (base as? MutationPresence.Present)?.value.orEmpty()
                    MutationPresence.Present(current + suffix.value)
                }
        }
    return MutableArgsMutators(registry, upsert)
}

private fun openMutableArgsEngine(
    storage: MutationJournalStorage,
    mutations: MutableArgsMutators,
): MutationEngine<RestartKey, String> =
    MutationEngine(
        registry = mutations.registry,
        server = RecordingRestartServer(),
        journal =
            StorageBackedMutationJournal(
                storage = storage,
                registrations = mutations.registry.registrations,
                clientId = "client-0",
                hydrateOnFirstUse = true,
            ),
        keyResolver = RecordingRestartResolver(),
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        clientId = "client-0",
    )

private fun restartMutators(
    argsVersion: Int = 1,
    argsCodec: MutationCodec<String> = FixtureStringArgsCodec,
    stales: (RestartKey, String) -> StaleSet<RestartKey> = noStales(),
): RestartMutators {
    lateinit var upsert: MutatorRef<RestartKey, String, String>
    lateinit var delete: MutatorRef<RestartKey, String, Unit>
    val registry =
        mutatorRegistry<RestartKey, String> {
            upsert =
                upsert(
                    id = "upsert",
                    version = argsVersion,
                    codec = argsCodec,
                    stales = stales,
                ) { base, suffix ->
                    val current = (base as? MutationPresence.Present)?.value.orEmpty()
                    MutationPresence.Present(current + suffix)
                }
            delete = delete(id = "delete", stales = noStales())
        }
    return RestartMutators(registry, upsert, delete)
}

private fun openRestartStore(
    storage: MutationJournalStorage,
    mutations: RestartMutators,
    server: MutationServer<RestartKey, String> = RecordingRestartServer(),
    resolver: MutationKeyResolver<RestartKey> = RecordingRestartResolver(),
    valueCodecVersion: Int = 1,
    valueCodec: MutationCodec<String> = FixtureStringArgsCodec,
    clock: WallClock? = null,
): MutationStore<RestartKey, String> =
    mutationStore(
        registry = mutations.registry,
        server = server,
        keyResolver = resolver,
        valueCodecVersion = valueCodecVersion,
        valueCodec = valueCodec,
    ) {
        fetcher { "confirmed" }
        journalStorage(storage)
        clock?.let { selected -> wallClock(selected) }
    }

private inline fun <K : StoreKey, V : Any, R> MutationStore<K, V>.use(
    block: (MutationStore<K, V>) -> R,
): R =
    try {
        block(this)
    } finally {
        close()
    }

private class RecordingRestartResolver : MutationKeyResolver<RestartKey> {
    val requests = mutableListOf<Pair<String, String>>()

    override suspend fun resolve(identity: MutationKeyIdentity): RestartKey {
        requests += identity.namespace to identity.canonicalId
        return RestartKey(
            identity.canonicalId,
            "resolver-process",
            StoreNamespace(identity.namespace),
        )
    }
}

private class RecordingRestartServer(
    private var cancelPushes: Int = 0,
    private val canonicalTarget: RestartKey? = null,
    private val beforePush: suspend (MutationPush<RestartKey, String>) -> Unit = {},
    private val retirementConfirmationCeiling: Long? = null,
) : MutationServer<RestartKey, String> {
    val pushes = mutableListOf<MutationPush<RestartKey, String>>()

    override suspend fun push(request: MutationPush<RestartKey, String>): MutationAck<RestartKey, String> {
        pushes += request
        beforePush(request)
        if (cancelPushes > 0) {
            cancelPushes -= 1
            throw CancellationException("transport outcome unknown")
        }
        return when (val mine = request.mine) {
            is MutationPresence.Present ->
                MutationPresentAck(
                    authoritative = mine.value,
                    etag = "etag-${request.clientSequence}",
                    canonicalKey = canonicalTarget,
                )
            MutationPresence.Absent -> MutationAbsentAck(etag = "etag-${request.clientSequence}")
        }
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(
            minOf(
                request.retiredThroughSequence,
                retirementConfirmationCeiling ?: request.retiredThroughSequence,
            ),
        )
}

private class RecordingRestartWriteHandle : StoreWriteHandle<RestartKey, String> {
    val applied = mutableListOf<Pair<String, String>>()
    val confirmed = mutableListOf<Pair<String, String?>>()

    override suspend fun apply(
        key: RestartKey,
        value: String,
    ) {
        applied += key.canonicalId() to value
    }

    override suspend fun markStale(key: RestartKey) = Unit

    override suspend fun confirmFresh(
        key: RestartKey,
        etag: String?,
    ) {
        confirmed += key.canonicalId() to etag
    }
}

private class StrictRecordingStringCodec(
    private val supportedVersions: Set<Int>,
    private val encodeVersion: Int? = null,
) : MutationCodec<String> {
    val encodedBuffers = mutableListOf<ByteArray>()
    val decodeVersions = mutableListOf<Int>()
    val decodeInputs = mutableListOf<ByteArray>()

    override fun encode(value: String): ByteArray =
        (encodeVersion?.let { version -> "store6-codec-$version:$value" } ?: value)
            .encodeToByteArray()
            .also(encodedBuffers::add)

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String {
        decodeVersions += version
        decodeInputs += bytes
        require(version in supportedVersions) {
            "unsupported codec version $version\nIllegalArgumentException raw marker"
        }
        val decoded = bytes.decodeToString()
        if (encodeVersion == null && !decoded.startsWith("store6-codec-")) return decoded
        val expectedPrefix = "store6-codec-$version:"
        require(decoded.startsWith(expectedPrefix)) {
            "codec payload does not match decoder version $version"
        }
        return decoded.removePrefix(expectedPrefix)
    }
}

private data class AttemptSnapshot(
    val clientId: String,
    val sequence: Long,
    val generation: Int,
    val identity: Pair<String, String>,
    val codecVersion: Int,
    val basePresence: MutationPresenceState,
    val baseBlob: List<Byte>?,
    val minePresence: MutationPresenceState,
    val mineBlob: List<Byte>?,
    val preconditionMetaPresent: Boolean,
    val preconditionWrittenAt: Long?,
    val preconditionEtag: String?,
    val advertisedPrefix: Long,
    val idempotencyKey: String,
    val preparedAt: Long,
    val conflictMetaPresent: Boolean?,
    val conflictWrittenAt: Long?,
    val conflictEtag: String?,
    val conflictReceivedAt: Long?,
)

private fun MutationAttemptRecord.snapshot(): AttemptSnapshot =
    AttemptSnapshot(
        clientId = clientId,
        sequence = clientSequence,
        generation = generation,
        identity = effectiveNamespace to effectiveCanonicalId,
        codecVersion = valueCodecVersion,
        basePresence = basePresence,
        baseBlob = baseBlob?.toList(),
        minePresence = minePresence,
        mineBlob = mineBlob?.toList(),
        preconditionMetaPresent = preconditionMetaPresent,
        preconditionWrittenAt = preconditionWrittenAt,
        preconditionEtag = preconditionEtag,
        advertisedPrefix = advertisedRetiredThroughSequence,
        idempotencyKey = generationIdempotencyKey,
        preparedAt = preparedAt,
        conflictMetaPresent = conflictMetaPresent,
        conflictWrittenAt = conflictWrittenAt,
        conflictEtag = conflictEtag,
        conflictReceivedAt = conflictReceivedAt,
    )

private data class PushSnapshot(
    val identity: Pair<String, String>,
    val clientId: String,
    val sequence: Long,
    val retiredPrefix: Long,
    val mutationId: String,
    val generation: Int,
    val idempotencyKey: String,
    val codecVersion: Int,
    val base: String?,
    val mine: String?,
    val metaWrittenAt: Long?,
    val metaEtag: String?,
)

private fun MutationPush<RestartKey, String>.snapshot(): PushSnapshot =
    PushSnapshot(
        identity = identity.namespace to identity.canonicalId,
        clientId = clientId,
        sequence = clientSequence,
        retiredPrefix = retiredThroughSequence,
        mutationId = mutationId,
        generation = generation,
        idempotencyKey = idempotencyKey,
        codecVersion = valueCodecVersion,
        base = (base as? MutationPresence.Present)?.value,
        mine = (mine as? MutationPresence.Present)?.value,
        metaWrittenAt = baseMeta?.writtenAtEpochMillis,
        metaEtag = baseMeta?.etag,
    )

private data class EffectSnapshot(
    val index: Int,
    val kind: MutationEffectKind,
    val namespace: String,
    val canonicalId: String?,
    val createdAt: Long,
    val disposition: MutationEffectDisposition = MutationEffectDisposition.PENDING,
    val completedAt: Long? = null,
)

private fun StoredEffectRecord.snapshot(): EffectSnapshot =
    EffectSnapshot(
        index = effectIndex,
        kind = kind,
        namespace = namespace,
        canonicalId = canonicalId,
        createdAt = createdAt,
        disposition = disposition,
        completedAt = completedAt,
    )

private data class AliasSnapshot(
    val sourceId: String,
    val targetId: String,
    val state: MutationAliasState,
    val creatorClientId: String,
    val creatorSequence: Long,
    val createdAt: Long,
    val activatedAt: Long?,
)

private fun MutationKeyAliasRecord.snapshot(): AliasSnapshot =
    AliasSnapshot(
        sourceId = sourceCanonicalId,
        targetId = targetCanonicalId,
        state = state,
        creatorClientId = createdByClientId,
        creatorSequence = createdBySequence,
        createdAt = createdAt,
        activatedAt = activatedAt,
    )

private data class TombstoneSnapshot(
    val creatorSequence: Long,
    val state: MutationTombstoneState,
    val createdAt: Long,
    val activatedAt: Long?,
    val supersededByClientId: String?,
    val supersededBySequence: Long?,
    val supersededAt: Long?,
)

private fun MutationKeyTombstoneRecord.snapshot(): TombstoneSnapshot =
    TombstoneSnapshot(
        creatorSequence = createdBySequence,
        state = state,
        createdAt = createdAt,
        activatedAt = activatedAt,
        supersededByClientId = supersededByClientId,
        supersededBySequence = supersededBySequence,
        supersededAt = supersededAt,
    )

private data class PendingSnapshot(
    val namespace: String,
    val canonicalId: String,
    val mutationId: String,
    val mutatorId: String,
    val state: MutationPendingState,
    val attempt: Int,
    val createdAt: Long,
)

private fun PendingIntent.snapshot(): PendingSnapshot =
    PendingSnapshot(namespace, canonicalId, mutationId, mutatorId, state, attempt, createdAtEpochMillis)

private data class DeadLetterSnapshot(
    val namespace: String,
    val canonicalId: String,
    val mutationId: String,
    val mutatorId: String,
    val generation: Int,
    val attempts: Int,
    val failureKind: MutationFailureKind,
    val failureDetail: String,
    val failureMessage: String,
    val failureAt: Long,
    val parkedAt: Long,
)

private fun DeadLetter.snapshot(): DeadLetterSnapshot =
    DeadLetterSnapshot(
        namespace = namespace,
        canonicalId = canonicalId,
        mutationId = mutationId,
        mutatorId = mutatorId,
        generation = generation,
        attempts = attempts,
        failureKind = failure.kind,
        failureDetail = failure.detail,
        failureMessage = failure.message,
        failureAt = failure.occurredAtEpochMillis,
        parkedAt = parkedAtEpochMillis,
    )

private data class InspectionSnapshot(
    val pending: List<PendingSnapshot>,
    val keyed: Map<String, List<PendingSnapshot>>,
    val deadLetters: List<DeadLetterSnapshot>,
)

private suspend fun phaseKeyedSnapshots(
    store: MutationStore<RestartKey, String>,
): Map<String, List<PendingSnapshot>> =
    (1..8).associate { sequence ->
        val id = "phase-$sequence"
        val namespace =
            when (sequence) {
                3, 4, 5, 6 -> StoreNamespace("inspection-$sequence")
                else -> StoreNamespace("mutations")
            }
        id to store.pending(RestartKey(id, "inspection", namespace)).map(PendingIntent::snapshot)
    }

private suspend fun seedEveryExecutionPhase(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        seedClient(transaction, lastAllocatedSequence = 8L)
        seedExecution(transaction, 1L, StoredPhase.UNPREPARED)
        seedExecution(transaction, 2L, StoredPhase.READY)
        seedExecution(transaction, 3L, StoredPhase.INFLIGHT, namespace = "inspection-3")
        seedExecution(transaction, 4L, StoredPhase.REFRESH_REQUIRED, namespace = "inspection-4")
        seedExecution(transaction, 5L, StoredPhase.ACKED, namespace = "inspection-5")
        seedExecution(transaction, 6L, StoredPhase.EFFECTS_PENDING, namespace = "inspection-6")
        seedExecution(transaction, 7L, StoredPhase.PARKED)
        seedExecution(transaction, 8L, StoredPhase.RETIRED)
    }
}

private suspend fun seedParkedFailure(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        seedClient(transaction, lastAllocatedSequence = 1L)
        seedExecution(transaction, 1L, StoredPhase.PARKED)
    }
}

private fun seedClient(
    transaction: MutationJournalTransaction,
    lastAllocatedSequence: Long,
) {
    seedNamedClient(transaction, "client-0", lastAllocatedSequence)
}

private fun seedNamedClient(
    transaction: MutationJournalTransaction,
    clientId: String,
    lastAllocatedSequence: Long,
) {
    transaction.insertClient(
        MutationClientRecord(1, clientId, 0L, 0L, 0L, createdAt = 10L),
    )
    transaction.advanceClient(
        MutationClientRecord(
            1,
            clientId,
            lastAllocatedSequence,
            0L,
            0L,
            createdAt = 10L,
        ),
    )
}

private fun seedAcknowledgedIntent(
    transaction: MutationJournalTransaction,
    clientId: String,
    sequence: Long,
    presence: MutationPresenceState,
    retire: Boolean = false,
) {
    val mutationId = "$clientId-mutation-$sequence"
    val isPresent = presence == MutationPresenceState.PRESENT
    transaction.insertIntent(
        recordVersion = 1,
        clientId = clientId,
        clientSequence = sequence,
        mutationId = mutationId,
        namespace = "mutations",
        canonicalId = "entity",
        mutatorId = if (isPresent) "upsert" else "delete",
        mutatorVersion = 1,
        argsBlob = if (isPresent) "+present".encodeToByteArray() else ByteArray(0),
        idempotencyRoot = "$clientId:$sequence",
        createdAt = 100L + sequence,
    )
    transaction.insertExecution(
        MutationExecutionRecord(clientId, sequence, StoredPhase.UNPREPARED, 0, 0, null, null, null),
    )
    val authoritativeBlob = if (isPresent) "authoritative-$clientId-$sequence".encodeToByteArray() else null
    transaction.insertAttempt(
        MutationAttemptRecord(
            clientId = clientId,
            clientSequence = sequence,
            generation = 1,
            effectiveNamespace = "mutations",
            effectiveCanonicalId = "entity",
            valueCodecVersion = 1,
            basePresence = MutationPresenceState.ABSENT,
            baseBlob = null,
            minePresence = presence,
            mineBlob = authoritativeBlob,
            preconditionMetaPresent = false,
            preconditionWrittenAt = null,
            preconditionEtag = null,
            advertisedRetiredThroughSequence = 0L,
            generationIdempotencyKey = "$clientId:$sequence:g1",
            preparedAt = 200L + sequence,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        ),
    )
    val ready =
        MutationExecutionRecord(clientId, sequence, StoredPhase.READY, 1, 0, null, null, null)
    transaction.advanceExecution(ready)
    transaction.advanceExecution(ready.copyPhase(StoredPhase.INFLIGHT))
    transaction.insertAck(
        MutationAckRecord(
            clientId = clientId,
            clientSequence = sequence,
            generation = 1,
            authoritativePresence = presence,
            authoritativeBlob = authoritativeBlob,
            valueCodecVersion = 1,
            etag = "etag-$clientId-$sequence",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 300L + sequence,
        ),
    )
    val acknowledged =
        MutationExecutionRecord(
            clientId,
            sequence,
            StoredPhase.ACKED,
            1,
            1,
            300L + sequence,
            null,
            null,
        )
    transaction.advanceExecution(acknowledged)
    if (retire) {
        transaction.advanceExecution(acknowledged.copyPhase(StoredPhase.EFFECTS_PENDING))
        transaction.advanceExecution(
            MutationExecutionRecord(
                clientId,
                sequence,
                StoredPhase.RETIRED,
                1,
                1,
                300L + sequence,
                null,
                400L + sequence,
            ),
        )
    }
}

private fun tombstone(
    canonicalId: String,
    creatorClientId: String,
    creatorSequence: Long,
    state: MutationTombstoneState,
    createdAt: Long,
    activatedAt: Long? = null,
    supersededByClientId: String? = null,
    supersededBySequence: Long? = null,
    supersededAt: Long? = null,
): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = "mutations",
        canonicalId = canonicalId,
        createdByClientId = creatorClientId,
        createdBySequence = creatorSequence,
        state = state,
        createdAt = createdAt,
        activatedAt = activatedAt,
        supersededByClientId = supersededByClientId,
        supersededBySequence = supersededBySequence,
        supersededAt = supersededAt,
    )

private fun insertActiveTombstone(
    transaction: MutationJournalTransaction,
    canonicalId: String,
    creatorClientId: String,
    creatorSequence: Long,
    createdAt: Long,
    activatedAt: Long,
) {
    transaction.insertTombstone(
        tombstone(
            canonicalId,
            creatorClientId,
            creatorSequence,
            MutationTombstoneState.PENDING,
            createdAt,
        ),
    )
    transaction.advanceTombstone(
        tombstone(
            canonicalId,
            creatorClientId,
            creatorSequence,
            MutationTombstoneState.ACTIVE,
            createdAt,
            activatedAt = activatedAt,
        ),
    )
}

private fun supersedeTombstone(
    transaction: MutationJournalTransaction,
    canonicalId: String,
    creatorClientId: String,
    creatorSequence: Long,
    createdAt: Long,
    activatedAt: Long,
    supersededByClientId: String,
    supersededBySequence: Long,
    supersededAt: Long,
) {
    transaction.advanceTombstone(
        tombstone(
            canonicalId,
            creatorClientId,
            creatorSequence,
            MutationTombstoneState.SUPERSEDED,
            createdAt,
            activatedAt = activatedAt,
            supersededByClientId = supersededByClientId,
            supersededBySequence = supersededBySequence,
            supersededAt = supersededAt,
        ),
    )
}

private fun insertActiveAlias(
    transaction: MutationJournalTransaction,
    source: String,
    target: String,
    creatorClientId: String,
    creatorSequence: Long,
) {
    val pending =
        MutationKeyAliasRecord(
            sourceNamespace = "mutations",
            sourceCanonicalId = source,
            targetNamespace = "mutations",
            targetCanonicalId = target,
            state = MutationAliasState.PENDING,
            createdByClientId = creatorClientId,
            createdBySequence = creatorSequence,
            createdAt = 10L,
            activatedAt = null,
        )
    transaction.insertAlias(pending)
    transaction.advanceAlias(
        MutationKeyAliasRecord(
            sourceNamespace = pending.sourceNamespace,
            sourceCanonicalId = pending.sourceCanonicalId,
            targetNamespace = pending.targetNamespace,
            targetCanonicalId = pending.targetCanonicalId,
            state = MutationAliasState.ACTIVE,
            createdByClientId = pending.createdByClientId,
            createdBySequence = pending.createdBySequence,
            createdAt = pending.createdAt,
            activatedAt = 11L,
        ),
    )
}

private suspend fun hydrateAndReadTombstones(
    storage: MutationJournalStorage,
    canonicalId: String,
): List<TombstoneSnapshot> {
    return openRestartStore(storage, restartMutators()).use { reopened ->
        reopened.pendingWrites()
        reopened.tombstonesForInspection("mutations", canonicalId)
            .map(MutationKeyTombstoneRecord::snapshot)
    }
}

private fun seedUnpreparedIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
    mutationId: String,
    canonicalId: String,
    mutatorId: String,
    mutatorVersion: Int,
    argsBlob: ByteArray,
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = "client-0",
        clientSequence = sequence,
        mutationId = mutationId,
        namespace = "mutations",
        canonicalId = canonicalId,
        mutatorId = mutatorId,
        mutatorVersion = mutatorVersion,
        argsBlob = argsBlob,
        idempotencyRoot = "client-0:$sequence",
        createdAt = 100L + sequence,
    )
    transaction.insertExecution(
        MutationExecutionRecord(
            clientId = "client-0",
            clientSequence = sequence,
            phase = StoredPhase.UNPREPARED,
            currentGeneration = 0,
            attempt = 0,
            lastAttemptAt = null,
            activeFailureId = null,
            retiredAt = null,
        ),
    )
}

private suspend fun seedCodecExecution(
    storage: MutationJournalStorage,
    target: StoredPhase,
    valueCodecVersion: Int,
    mutatorId: String = "upsert",
) {
    require(target == StoredPhase.READY || target == StoredPhase.ACKED)
    storage.transaction { transaction ->
        seedClient(transaction, lastAllocatedSequence = 1L)
        seedUnpreparedIntent(
            transaction,
            sequence = 1L,
            mutationId = "mutation-1",
            canonicalId = "codec-seed",
            mutatorId = mutatorId,
            mutatorVersion = 1,
            argsBlob = "+mine".encodeToByteArray(),
        )
        transaction.insertAttempt(
            MutationAttemptRecord(
                clientId = "client-0",
                clientSequence = 1L,
                generation = 1,
                effectiveNamespace = "mutations",
                effectiveCanonicalId = "codec-seed",
                valueCodecVersion = valueCodecVersion,
                basePresence = MutationPresenceState.PRESENT,
                baseBlob = "confirmed".encodeToByteArray(),
                minePresence = MutationPresenceState.PRESENT,
                mineBlob = "confirmed+mine".encodeToByteArray(),
                preconditionMetaPresent = false,
                preconditionWrittenAt = null,
                preconditionEtag = null,
                advertisedRetiredThroughSequence = 0L,
                generationIdempotencyKey = "client-0:1:g1",
                preparedAt = 200L,
                conflictMetaPresent = null,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = null,
            ),
        )
        val ready =
            MutationExecutionRecord(
                clientId = "client-0",
                clientSequence = 1L,
                phase = StoredPhase.READY,
                currentGeneration = 1,
                attempt = 0,
                lastAttemptAt = null,
                activeFailureId = null,
                retiredAt = null,
            )
        transaction.advanceExecution(ready)
        if (target == StoredPhase.ACKED) {
            transaction.advanceExecution(ready.copyPhase(StoredPhase.INFLIGHT))
            transaction.insertAck(
                MutationAckRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    generation = 1,
                    authoritativePresence = MutationPresenceState.PRESENT,
                    authoritativeBlob = "authoritative".encodeToByteArray(),
                    valueCodecVersion = valueCodecVersion,
                    etag = "etag-1",
                    canonicalTargetNamespace = null,
                    canonicalTargetId = null,
                    receivedAt = 300L,
                ),
            )
            transaction.advanceExecution(
                MutationExecutionRecord(
                    clientId = "client-0",
                    clientSequence = 1L,
                    phase = StoredPhase.ACKED,
                    currentGeneration = 1,
                    attempt = 1,
                    lastAttemptAt = 300L,
                    activeFailureId = null,
                    retiredAt = null,
                ),
            )
        }
    }
}

private fun seedExecution(
    transaction: MutationJournalTransaction,
    sequence: Long,
    target: StoredPhase,
    namespace: String = "mutations",
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = "client-0",
        clientSequence = sequence,
        mutationId = "mutation-$sequence",
        namespace = namespace,
        canonicalId = "phase-$sequence",
        mutatorId = "upsert",
        mutatorVersion = 1,
        argsBlob = "+$sequence".encodeToByteArray(),
        idempotencyRoot = "client-0:$sequence",
        createdAt = 100L + sequence,
    )
    var execution =
        MutationExecutionRecord(
            "client-0",
            sequence,
            StoredPhase.UNPREPARED,
            0,
            0,
            null,
            null,
            null,
        )
    transaction.insertExecution(execution)
    if (target == StoredPhase.UNPREPARED) return
    if (target == StoredPhase.PARKED) {
        val failure =
            transaction.appendFailure(
                clientId = "client-0",
                clientSequence = sequence,
                generation = 0,
                kind = MutationFailureKind.CODEC,
                detail = "codec-version",
                message = "unsupported durable codec",
                occurredAt = 700L + sequence,
            )
        transaction.advanceExecution(
            MutationExecutionRecord(
                "client-0",
                sequence,
                StoredPhase.PARKED,
                0,
                0,
                null,
                failure.failureId,
                null,
            ),
        )
        return
    }

    val attempt = seededAttempt(sequence, namespace)
    transaction.insertAttempt(attempt)
    execution =
        MutationExecutionRecord(
            "client-0",
            sequence,
            StoredPhase.READY,
            1,
            0,
            null,
            null,
            null,
        )
    transaction.advanceExecution(execution)
    if (target == StoredPhase.READY) return

    execution = execution.copyPhase(StoredPhase.INFLIGHT)
    transaction.advanceExecution(execution)
    if (target == StoredPhase.INFLIGHT) return
    if (target == StoredPhase.REFRESH_REQUIRED) {
        transaction.recordConflictReceipt(
            MutationAttemptRecord(
                clientId = attempt.clientId,
                clientSequence = attempt.clientSequence,
                generation = attempt.generation,
                effectiveNamespace = attempt.effectiveNamespace,
                effectiveCanonicalId = attempt.effectiveCanonicalId,
                valueCodecVersion = attempt.valueCodecVersion,
                basePresence = attempt.basePresence,
                baseBlob = attempt.baseBlob,
                minePresence = attempt.minePresence,
                mineBlob = attempt.mineBlob,
                preconditionMetaPresent = attempt.preconditionMetaPresent,
                preconditionWrittenAt = attempt.preconditionWrittenAt,
                preconditionEtag = attempt.preconditionEtag,
                advertisedRetiredThroughSequence = attempt.advertisedRetiredThroughSequence,
                generationIdempotencyKey = attempt.generationIdempotencyKey,
                preparedAt = attempt.preparedAt,
                conflictMetaPresent = false,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = 500L + sequence,
            ),
        )
        transaction.advanceExecution(
            MutationExecutionRecord(
                "client-0",
                sequence,
                StoredPhase.REFRESH_REQUIRED,
                1,
                1,
                500L + sequence,
                null,
                null,
            ),
        )
        return
    }

    transaction.insertAck(seededAck(sequence))
    execution =
        MutationExecutionRecord(
            "client-0",
            sequence,
            StoredPhase.ACKED,
            1,
            1,
            500L + sequence,
            null,
            null,
        )
    transaction.advanceExecution(execution)
    if (target == StoredPhase.ACKED) return

    execution = execution.copyPhase(StoredPhase.EFFECTS_PENDING)
    transaction.advanceExecution(execution)
    if (target == StoredPhase.EFFECTS_PENDING) return

    check(target == StoredPhase.RETIRED)
    transaction.advanceExecution(
        MutationExecutionRecord(
            "client-0",
            sequence,
            StoredPhase.RETIRED,
            1,
            1,
            500L + sequence,
            null,
            900L + sequence,
        ),
    )
}

private fun seededAttempt(
    sequence: Long,
    namespace: String = "mutations",
): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = "client-0",
        clientSequence = sequence,
        generation = 1,
        effectiveNamespace = namespace,
        effectiveCanonicalId = "phase-$sequence",
        valueCodecVersion = 1,
        basePresence = MutationPresenceState.ABSENT,
        baseBlob = null,
        minePresence = MutationPresenceState.PRESENT,
        mineBlob = "+$sequence".encodeToByteArray(),
        preconditionMetaPresent = false,
        preconditionWrittenAt = null,
        preconditionEtag = null,
        advertisedRetiredThroughSequence = 0L,
        generationIdempotencyKey = "client-0:$sequence:g1",
        preparedAt = 200L + sequence,
        conflictMetaPresent = null,
        conflictWrittenAt = null,
        conflictEtag = null,
        conflictReceivedAt = null,
    )

private fun seededAck(sequence: Long): MutationAckRecord =
    MutationAckRecord(
        clientId = "client-0",
        clientSequence = sequence,
        generation = 1,
        authoritativePresence = MutationPresenceState.PRESENT,
        authoritativeBlob = "+$sequence".encodeToByteArray(),
        valueCodecVersion = 1,
        etag = "etag-$sequence",
        canonicalTargetNamespace = null,
        canonicalTargetId = null,
        receivedAt = 500L + sequence,
    )

private fun MutationExecutionRecord.copyPhase(phase: StoredPhase): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId,
        clientSequence,
        phase,
        currentGeneration,
        attempt,
        lastAttemptAt,
        activeFailureId,
        retiredAt,
    )

private class InjectedPersistenceFailure : RuntimeException("injected journal write failure")

private class ArmableFailingStorage(
    private val delegate: MutationJournalStorage,
) : MutationJournalStorage {
    var failNextReadyAdvance: Boolean = false

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R =
        delegate.transaction { transaction ->
            block(
                object : MutationJournalTransaction by transaction {
                    override fun advanceExecution(record: MutationExecutionRecord) {
                        if (failNextReadyAdvance && record.phase == StoredPhase.READY) {
                            failNextReadyAdvance = false
                            throw InjectedPersistenceFailure()
                        }
                        transaction.advanceExecution(record)
                    }
                },
            )
        }
}

private class ArmablePhaseFailingStorage(
    private val delegate: MutationJournalStorage,
) : MutationJournalStorage {
    var failNextPhase: StoredPhase? = null

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R =
        delegate.transaction { transaction ->
            block(
                object : MutationJournalTransaction by transaction {
                    override fun advanceExecution(record: MutationExecutionRecord) {
                        if (record.phase == failNextPhase) {
                            failNextPhase = null
                            throw InjectedPersistenceFailure()
                        }
                        transaction.advanceExecution(record)
                    }
                },
            )
        }
}
