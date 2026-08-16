@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectKind
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationPruningRegressionTest {
    @Test
    fun ackAndPendingAliasCommit_isAtomicAcrossCrash() =
        runTest(timeout = 25.seconds) {
            val rawStorage = InMemoryMutationJournalStorage()
            val killedStorage = EngineKillPointJournalStorage(rawStorage)
            val fixture = pruningFixture()
            val source = MutationsTestKey("provisional-ack")
            val canonical = MutationsTestKey("canonical-ack")
            val server =
                CountingAckServer(
                    canonicalKey = canonical,
                    retirementConfirmationCeiling = 0L,
                )
            val adoption = CountingAdoptionHandle()
            val first = openPruningEngine(killedStorage, fixture.registry, server, adoption)

            first.mutate(source, fixture.append, "+mine")
            killedStorage.arm(EngineJournalKillPoint.AFTER_ALIAS_INSERT_COMMIT)
            val crash =
                assertFailsWith<EngineJournalCrashException> {
                    first.drain(source)
                }
            assertEquals(EngineJournalKillPoint.AFTER_ALIAS_INSERT_COMMIT, crash.killPoint)
            assertEquals(true, crash.aliasCommitIncludedAckAndAcked)
            assertEquals(1, server.pushCount)
            assertEquals(0, adoption.applyCount)
            assertEquals(0, adoption.confirmFreshCount)

            rawStorage.transaction { transaction ->
                assertEquals(
                    StoredExecutionPhase.ACKED,
                    transaction.executions(PRUNING_CLIENT_ID).single().phase,
                )
                assertEquals(1, transaction.acks(PRUNING_CLIENT_ID).size)
                assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            }

            openPruningEngine(rawStorage, fixture.registry, server, adoption).drain(source)
            assertEquals(1, server.pushCount)
            assertEquals(1, adoption.applyCount)
            assertEquals(1, adoption.confirmFreshCount)
            rawStorage.transaction { transaction ->
                assertEquals(
                    StoredExecutionPhase.RETIRED,
                    transaction.executions(PRUNING_CLIENT_ID).single().phase,
                )
                assertEquals(MutationAliasState.ACTIVE, transaction.aliases().single().state)
            }
        }

    @Test
    fun crashBetweenRetireAndPrune_neverPermitsDoubleApply() =
        runTest(timeout = 25.seconds) {
            val rawStorage = InMemoryMutationJournalStorage()
            val killedStorage = EngineKillPointJournalStorage(rawStorage)
            val fixture = pruningFixture()
            val server = CountingAckServer()
            val adoption = CountingAdoptionHandle()
            val key = MutationsTestKey("retire-prune")
            val first = openPruningEngine(killedStorage, fixture.registry, server, adoption)

            first.mutate(key, fixture.append, "+mine")
            killedStorage.arm(EngineJournalKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT)
            val crash =
                assertFailsWith<EngineJournalCrashException> {
                    first.drain(key)
                }
            assertEquals(EngineJournalKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT, crash.killPoint)
            assertEquals(1, server.pushCount)
            assertEquals(1, adoption.applyCount)
            assertEquals(1, adoption.confirmFreshCount)
            rawStorage.transaction { transaction ->
                assertEquals(StoredExecutionPhase.RETIRED, transaction.executions(PRUNING_CLIENT_ID).single().phase)
                assertEquals(1L, requireNotNull(transaction.client(PRUNING_CLIENT_ID)).retiredThroughSequence)
            }

            openPruningEngine(rawStorage, fixture.registry, server, adoption).drain(key)
            assertEquals(1, server.pushCount)
            assertEquals(1, adoption.applyCount)
            assertEquals(1, adoption.confirmFreshCount)

            rawStorage.transaction { transaction ->
                transaction.confirmRetiredThrough(
                    clientId = PRUNING_CLIENT_ID,
                    requestedThroughSequence = 1L,
                    serverConfirmedThroughSequence = 1L,
                )
                transaction.prune(PRUNING_CLIENT_ID, serverConfirmedRetiredThroughSequence = 1L)
            }
            openPruningEngine(rawStorage, fixture.registry, server, adoption).drain(key)
            assertEquals(1, server.pushCount)
            assertEquals(1, adoption.applyCount)
            assertEquals(1, adoption.confirmFreshCount)
        }

    /**
     * Ordinary prune removes rows only at or below the persisted server-confirmed prefix;
     * alias redirects and active or pending tombstone generations always survive.
     */
    @Test
    fun pruneNeverExceedsServerConfirmedPrefix_evenAfterCrashLoop() =
        runTest(timeout = 25.seconds) {
            var storage: MutationJournalStorage = InMemoryMutationJournalStorage()
            seedPrunableJournal(storage)
            val protected = storage.transaction(::protectedJournalSnapshot)

            listOf(
                EngineJournalKillPoint.BEFORE_PRUNE,
                EngineJournalKillPoint.BEFORE_PRUNE_COMMIT,
                EngineJournalKillPoint.AFTER_PRUNE_COMMIT,
            ).forEach { point ->
                val killed = EngineKillPointJournalStorage(storage)
                killed.arm(point)
                val crash =
                    assertFailsWith<EngineJournalCrashException> {
                        killed.transaction { transaction ->
                            transaction.prune(
                                PRUNING_CLIENT_ID,
                                serverConfirmedRetiredThroughSequence = 2L,
                            )
                        }
                    }
                assertEquals(point, crash.killPoint)
                storage = reopenInMemory(storage)
                assertEquals(protected, storage.transaction(::protectedJournalSnapshot), "after $point")
                assertClientInvariant(storage)
            }

            storage.transaction { transaction ->
                transaction.prune(PRUNING_CLIENT_ID, serverConfirmedRetiredThroughSequence = 2L)
            }
            assertEquals(protected, storage.transaction(::protectedJournalSnapshot), "after final prune")
            assertClientInvariant(storage)
        }

    @Test
    fun killPointBeforeRetirementCommit_leavesEffectsPendingAndNoRevision() =
        runTest(timeout = 25.seconds) {
            val composedStorage = InMemoryMutationJournalStorage()
            seedEffectsPendingJournal(composedStorage)
            val killedComposition = EngineKillPointJournalStorage(composedStorage)
            killedComposition.arm(EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT)

            val compositionCrash =
                assertFailsWith<EngineJournalCrashException> {
                    finalizeSeededRetirement(killedComposition)
                }
            assertEquals(
                EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT,
                compositionCrash.killPoint,
            )
            composedStorage.transaction { transaction ->
                assertEquals(StoredExecutionPhase.EFFECTS_PENDING, transaction.executions(PRUNING_CLIENT_ID).single().phase)
                val effect = transaction.effects(PRUNING_CLIENT_ID).single()
                assertEquals(MutationEffectDisposition.PENDING, effect.disposition)
                assertEquals(null, effect.completedAt)
                assertEquals(0L, requireNotNull(transaction.client(PRUNING_CLIENT_ID)).retiredThroughSequence)
                assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            }

            val rawStorage = InMemoryMutationJournalStorage()
            val killedStorage = EngineKillPointJournalStorage(rawStorage)
            val fixture = pruningFixture()
            val source = MutationsTestKey("provisional")
            val canonical = MutationsTestKey("canonical")
            val server = CountingAckServer(canonical)
            val adoption = CountingAdoptionHandle()
            val engine = openPruningEngine(killedStorage, fixture.registry, server, adoption)
            engine.mutate(source, fixture.append, "+mine")
            val revisionBefore = engine.aliasRevision(source.identity()).value
            killedStorage.arm(EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT)

            val engineCrash =
                assertFailsWith<EngineJournalCrashException> {
                    engine.drain(source)
                }
            assertEquals(
                EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT,
                engineCrash.killPoint,
            )
            assertEquals(revisionBefore, engine.aliasRevision(source.identity()).value)
            rawStorage.transaction { transaction ->
                assertEquals(StoredExecutionPhase.EFFECTS_PENDING, transaction.executions(PRUNING_CLIENT_ID).single().phase)
                assertTrue(transaction.effects(PRUNING_CLIENT_ID).isEmpty())
                assertEquals(0L, requireNotNull(transaction.client(PRUNING_CLIENT_ID)).retiredThroughSequence)
                assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            }
        }
}

private enum class EngineJournalKillPoint {
    AFTER_ALIAS_INSERT_COMMIT,
    BEFORE_RETIREMENT_FINALIZATION_COMMIT,
    AFTER_RETIREMENT_FINALIZATION_COMMIT,
    BEFORE_PRUNE,
    BEFORE_PRUNE_COMMIT,
    AFTER_PRUNE_COMMIT,
}

private class EngineJournalCrashException(
    val killPoint: EngineJournalKillPoint,
    val aliasCommitIncludedAckAndAcked: Boolean? = null,
) : RuntimeException("Simulated journal crash at $killPoint")

/** Module-local red-first sibling of the public testing-kit decorator. */
private class EngineKillPointJournalStorage(
    private val delegate: MutationJournalStorage,
) : MutationJournalStorage {
    private val gate = Mutex()
    private var armed: EngineJournalKillPoint? = null

    suspend fun arm(killPoint: EngineJournalKillPoint) {
        gate.withLock {
            check(armed == null) { "A journal kill point is already armed: $armed" }
            armed = killPoint
        }
    }

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R =
        gate.withLock {
            var committedAliasInsert = false
            var aliasCommitIncludedAckAndAcked = false
            var committedRetirementFinalization = false
            var committedPrune = false
            val result =
                delegate.transaction { transaction ->
                    val observed =
                        EngineObservingJournalTransaction(
                            delegate = transaction,
                            beforePrune = {
                                if (armed == EngineJournalKillPoint.BEFORE_PRUNE) {
                                    crash(EngineJournalKillPoint.BEFORE_PRUNE)
                                }
                            },
                        )
                    val value = block(observed)
                    if (
                        observed.sawRetirementFinalization &&
                        armed == EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT
                    ) {
                        crash(EngineJournalKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT)
                    }
                    if (observed.sawPrune && armed == EngineJournalKillPoint.BEFORE_PRUNE_COMMIT) {
                        crash(EngineJournalKillPoint.BEFORE_PRUNE_COMMIT)
                    }
                    committedRetirementFinalization = observed.sawRetirementFinalization
                    committedAliasInsert = observed.sawAliasInsert
                    aliasCommitIncludedAckAndAcked =
                        observed.sawAliasInsert && observed.sawAckInsert && observed.sawAckedAdvance
                    committedPrune = observed.sawPrune
                    value
                }
            if (
                committedAliasInsert &&
                armed == EngineJournalKillPoint.AFTER_ALIAS_INSERT_COMMIT
            ) {
                crash(
                    EngineJournalKillPoint.AFTER_ALIAS_INSERT_COMMIT,
                    aliasCommitIncludedAckAndAcked = aliasCommitIncludedAckAndAcked,
                )
            }
            if (
                committedRetirementFinalization &&
                armed == EngineJournalKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT
            ) {
                crash(EngineJournalKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT)
            }
            if (committedPrune && armed == EngineJournalKillPoint.AFTER_PRUNE_COMMIT) {
                crash(EngineJournalKillPoint.AFTER_PRUNE_COMMIT)
            }
            result
        }

    private fun crash(
        killPoint: EngineJournalKillPoint,
        aliasCommitIncludedAckAndAcked: Boolean? = null,
    ): Nothing {
        check(armed == killPoint)
        armed = null
        throw EngineJournalCrashException(killPoint, aliasCommitIncludedAckAndAcked)
    }
}

private class EngineObservingJournalTransaction(
    private val delegate: MutationJournalTransaction,
    private val beforePrune: () -> Unit,
) : MutationJournalTransaction by delegate {
    private val entryPhases = mutableMapOf<Pair<String, Long>, StoredExecutionPhase>()

    var sawAliasInsert: Boolean = false
        private set
    var sawAckInsert: Boolean = false
        private set
    var sawAckedAdvance: Boolean = false
        private set
    var sawRetirementFinalization: Boolean = false
        private set
    var sawPrune: Boolean = false
        private set

    override fun insertAlias(record: MutationKeyAliasRecord) {
        delegate.insertAlias(record)
        sawAliasInsert = true
    }

    override fun insertAck(record: MutationAckRecord) {
        delegate.insertAck(record)
        sawAckInsert = true
    }

    override fun advanceExecution(record: MutationExecutionRecord) {
        val identity = record.clientId to record.clientSequence
        val entryPhase =
            entryPhases.getOrPut(identity) {
                requireNotNull(
                    delegate.executions(record.clientId).firstOrNull { previous ->
                        previous.clientSequence == record.clientSequence
                    },
                ).phase
            }
        delegate.advanceExecution(record)
        if (record.phase == StoredExecutionPhase.ACKED) {
            sawAckedAdvance = true
        }
        if (
            entryPhase == StoredExecutionPhase.EFFECTS_PENDING &&
            record.phase == StoredExecutionPhase.RETIRED
        ) {
            sawRetirementFinalization = true
        }
    }

    override fun prune(
        clientId: String,
        serverConfirmedRetiredThroughSequence: Long,
    ) {
        beforePrune()
        delegate.prune(clientId, serverConfirmedRetiredThroughSequence)
        sawPrune = true
    }
}

private data class PruningFixture(
    val registry: MutatorRegistry<MutationsTestKey, String>,
    val append: MutatorRef<MutationsTestKey, String, String>,
)

private fun pruningFixture(): PruningFixture {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    val registry =
        mutatorRegistry<MutationsTestKey, String> {
            append =
                upsert(
                    id = "pruning-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    val value = (base as? MutationPresence.Present)?.value.orEmpty()
                    MutationPresence.Present(value + suffix)
                }
        }
    return PruningFixture(registry, append)
}

private fun openPruningEngine(
    storage: MutationJournalStorage,
    registry: MutatorRegistry<MutationsTestKey, String>,
    server: MutationServer<MutationsTestKey, String>,
    adoption: StoreWriteHandle<MutationsTestKey, String>,
): MutationEngine<MutationsTestKey, String> =
    MutationEngine(
        registry = registry,
        server = server,
        journal =
            StorageBackedMutationJournal(
                storage = storage,
                registrations = registry.registrations,
                clientId = PRUNING_CLIENT_ID,
                hydrateOnFirstUse = true,
            ),
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        baseReader = { "base" },
        clientId = PRUNING_CLIENT_ID,
    ).also { engine -> engine.bind(adoption) }

private class CountingAckServer(
    private val canonicalKey: MutationsTestKey? = null,
    private val retirementConfirmationCeiling: Long? = null,
) : MutationServer<MutationsTestKey, String> {
    var pushCount: Int = 0
        private set

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        pushCount += 1
        return MutationPresentAck(
            authoritative = "authoritative-${request.clientSequence}",
            etag = "etag-${request.clientSequence}",
            canonicalKey = canonicalKey,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(
            minOf(
                request.retiredThroughSequence,
                retirementConfirmationCeiling ?: request.retiredThroughSequence,
            ),
        )
}

private class CountingAdoptionHandle : StoreWriteHandle<MutationsTestKey, String> {
    var applyCount: Int = 0
        private set
    var confirmFreshCount: Int = 0
        private set

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        applyCount += 1
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) {
        confirmFreshCount += 1
    }
}

private fun reopenInMemory(storage: MutationJournalStorage): MutationJournalStorage = storage

private suspend fun assertClientInvariant(storage: MutationJournalStorage) {
    val client = requireNotNull(storage.transaction { it.client(PRUNING_CLIENT_ID) })
    assertTrue(client.serverConfirmedRetiredThroughSequence <= client.retiredThroughSequence)
    assertTrue(client.retiredThroughSequence <= client.lastAllocatedSequence)
}

private suspend fun seedEffectsPendingJournal(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.insertClient(pruningClient())
        transaction.advanceClient(pruningClient(lastAllocated = 1L))
        insertPruningIntent(transaction, sequence = 1L)
        transaction.insertExecution(pruningExecution(sequence = 1L))
        transaction.insertAttempt(pruningAttempt(sequence = 1L))
        transaction.insertEffect(pruningEffect(sequence = 1L))
        transaction.advanceExecution(
            pruningExecution(sequence = 1L, phase = StoredExecutionPhase.READY, generation = 1),
        )
        transaction.advanceExecution(
            pruningExecution(sequence = 1L, phase = StoredExecutionPhase.INFLIGHT, generation = 1),
        )
        transaction.insertAck(pruningAck(sequence = 1L))
        transaction.advanceExecution(
            pruningExecution(
                sequence = 1L,
                phase = StoredExecutionPhase.ACKED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
            ),
        )
        transaction.advanceExecution(
            pruningExecution(
                sequence = 1L,
                phase = StoredExecutionPhase.EFFECTS_PENDING,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
            ),
        )
        transaction.insertAlias(pruningAlias())
    }
}

private suspend fun finalizeSeededRetirement(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.advanceEffect(
            pruningEffect(
                sequence = 1L,
                disposition = MutationEffectDisposition.APPLIED,
                completedAt = 50L,
            ),
        )
        transaction.advanceAlias(
            pruningAlias(state = MutationAliasState.ACTIVE, activatedAt = 50L),
        )
        transaction.advanceExecution(
            pruningExecution(
                sequence = 1L,
                phase = StoredExecutionPhase.RETIRED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
                retiredAt = 50L,
            ),
        )
        transaction.advanceClient(pruningClient(lastAllocated = 1L, retiredThrough = 1L))
    }
}

private suspend fun seedPrunableJournal(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.insertClient(pruningClient())
        transaction.advanceClient(pruningClient(lastAllocated = 4L))
        (1L..4L).forEach { sequence ->
            insertPruningIntent(transaction, sequence)
            transaction.insertExecution(pruningExecution(sequence))
        }
    }
    (1L..4L).forEach { sequence ->
        storage.transaction { transaction ->
            transaction.insertAttempt(pruningAttempt(sequence))
            transaction.insertEffect(pruningEffect(sequence))
            transaction.advanceExecution(
                pruningExecution(sequence, phase = StoredExecutionPhase.READY, generation = 1),
            )
            transaction.advanceExecution(
                pruningExecution(sequence, phase = StoredExecutionPhase.INFLIGHT, generation = 1),
            )
            transaction.insertAck(pruningAck(sequence))
            transaction.appendFailure(
                clientId = PRUNING_CLIENT_ID,
                clientSequence = sequence,
                generation = 1,
                kind = MutationFailureKind.EFFECT,
                detail = "retained-$sequence",
                message = "retained evidence $sequence",
                occurredAt = 40L + sequence,
            )
            transaction.advanceExecution(
                pruningExecution(
                    sequence,
                    phase = StoredExecutionPhase.ACKED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceExecution(
                pruningExecution(
                    sequence,
                    phase = StoredExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceEffect(
                pruningEffect(
                    sequence,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 50L + sequence,
                ),
            )
            transaction.advanceExecution(
                pruningExecution(
                    sequence,
                    phase = StoredExecutionPhase.RETIRED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                    retiredAt = 50L + sequence,
                ),
            )
        }
    }
    storage.transaction { transaction ->
        transaction.advanceClient(pruningClient(lastAllocated = 4L, retiredThrough = 4L))
        transaction.confirmRetiredThrough(
            clientId = PRUNING_CLIENT_ID,
            requestedThroughSequence = 2L,
            serverConfirmedThroughSequence = 2L,
        )
        transaction.insertAlias(pruningAlias(sourceId = "alias-source", targetId = "alias-target", sequence = 3L))
        transaction.advanceAlias(
            pruningAlias(
                sourceId = "alias-source",
                targetId = "alias-target",
                sequence = 3L,
                state = MutationAliasState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.insertTombstone(pruningTombstone(sequence = 3L, canonicalId = "protected-active"))
        transaction.advanceTombstone(
            pruningTombstone(
                sequence = 3L,
                canonicalId = "protected-active",
                state = MutationTombstoneState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.insertTombstone(pruningTombstone(sequence = 1L, canonicalId = "eligible-superseded"))
        transaction.advanceTombstone(
            pruningTombstone(
                sequence = 1L,
                canonicalId = "eligible-superseded",
                state = MutationTombstoneState.ACTIVE,
                activatedAt = 60L,
            ),
        )
        transaction.advanceTombstone(
            pruningTombstone(
                sequence = 1L,
                canonicalId = "eligible-superseded",
                state = MutationTombstoneState.SUPERSEDED,
                activatedAt = 60L,
                supersededBySequence = 2L,
                supersededAt = 65L,
            ),
        )
    }
}

private data class ProtectedJournalSnapshot(
    val rows: List<String>,
)

private fun protectedJournalSnapshot(transaction: MutationJournalTransaction): ProtectedJournalSnapshot {
    val client = requireNotNull(transaction.client(PRUNING_CLIENT_ID))
    val rows = mutableListOf<String>()
    rows +=
        "client:${client.recordVersion}:${client.clientId}:${client.lastAllocatedSequence}:" +
        "${client.retiredThroughSequence}:${client.serverConfirmedRetiredThroughSequence}:${client.createdAt}"
    transaction.intents(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "intent:${row.rowId}:${row.recordVersion}:${row.clientId}:${row.clientSequence}:" +
            "${row.mutationId}:${row.namespace}:${row.canonicalId}:${row.mutatorId}:" +
            "${row.mutatorVersion}:${row.argsBlob.toList()}:${row.idempotencyRoot}:${row.createdAt}"
    }
    transaction.executions(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "execution:${row.clientId}:${row.clientSequence}:${row.phase}:${row.currentGeneration}:" +
            "${row.attempt}:${row.lastAttemptAt}:${row.activeFailureId}:${row.retiredAt}"
    }
    transaction.attempts(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "attempt:${row.clientId}:${row.clientSequence}:${row.generation}:${row.effectiveNamespace}:" +
            "${row.effectiveCanonicalId}:${row.valueCodecVersion}:${row.basePresence}:${row.baseBlob?.toList()}:" +
            "${row.minePresence}:${row.mineBlob?.toList()}:${row.preconditionMetaPresent}:" +
            "${row.preconditionWrittenAt}:${row.preconditionEtag}:${row.advertisedRetiredThroughSequence}:" +
            "${row.generationIdempotencyKey}:${row.preparedAt}:${row.conflictMetaPresent}:" +
            "${row.conflictWrittenAt}:${row.conflictEtag}:${row.conflictReceivedAt}"
    }
    transaction.acks(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "ack:${row.clientId}:${row.clientSequence}:${row.generation}:${row.authoritativePresence}:" +
            "${row.authoritativeBlob?.toList()}:${row.valueCodecVersion}:${row.etag}:" +
            "${row.canonicalTargetNamespace}:${row.canonicalTargetId}:${row.receivedAt}"
    }
    transaction.failures(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "failure:${row.failureId}:${row.clientId}:${row.clientSequence}:${row.generation}:" +
            "${row.kind}:${row.detail}:${row.message}:${row.occurredAt}"
    }
    transaction.effects(PRUNING_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "effect:${row.clientId}:${row.clientSequence}:${row.effectIndex}:${row.kind}:" +
            "${row.namespace}:${row.canonicalId}:${row.createdAt}:${row.disposition}:${row.completedAt}"
    }
    transaction.aliases().forEach { row ->
        rows +=
            "alias:${row.sourceNamespace}:${row.sourceCanonicalId}:${row.targetNamespace}:" +
            "${row.targetCanonicalId}:${row.state}:${row.createdByClientId}:${row.createdBySequence}:" +
            "${row.createdAt}:${row.activatedAt}"
    }
    transaction.tombstones().filter { it.state != MutationTombstoneState.SUPERSEDED }.forEach { row ->
        rows +=
            "tombstone:${row.namespace}:${row.canonicalId}:${row.createdByClientId}:" +
            "${row.createdBySequence}:${row.state}:${row.createdAt}:${row.activatedAt}:" +
            "${row.supersededByClientId}:${row.supersededBySequence}:${row.supersededAt}"
    }
    return ProtectedJournalSnapshot(rows)
}

private fun insertPruningIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = PRUNING_CLIENT_ID,
        clientSequence = sequence,
        mutationId = "seed-mutation-$sequence",
        namespace = "mutations",
        canonicalId = "item-$sequence",
        mutatorId = "pruning-append",
        mutatorVersion = 1,
        argsBlob = byteArrayOf(sequence.toByte()),
        idempotencyRoot = "root-$sequence",
        createdAt = 10L + sequence,
    )
}

private fun pruningClient(
    lastAllocated: Long = 0L,
    retiredThrough: Long = 0L,
): MutationClientRecord =
    MutationClientRecord(
        recordVersion = 1,
        clientId = PRUNING_CLIENT_ID,
        lastAllocatedSequence = lastAllocated,
        retiredThroughSequence = retiredThrough,
        serverConfirmedRetiredThroughSequence = 0L,
        createdAt = 1L,
    )

private fun pruningExecution(
    sequence: Long,
    phase: StoredExecutionPhase = StoredExecutionPhase.UNPREPARED,
    generation: Int = 0,
    attempt: Int = 0,
    lastAttemptAt: Long? = null,
    retiredAt: Long? = null,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = PRUNING_CLIENT_ID,
        clientSequence = sequence,
        phase = phase,
        currentGeneration = generation,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = null,
        retiredAt = retiredAt,
    )

private fun pruningAttempt(sequence: Long): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = PRUNING_CLIENT_ID,
        clientSequence = sequence,
        generation = 1,
        effectiveNamespace = "mutations",
        effectiveCanonicalId = "item-$sequence",
        valueCodecVersion = 1,
        basePresence = MutationPresenceState.PRESENT,
        baseBlob = byteArrayOf(1),
        minePresence = MutationPresenceState.PRESENT,
        mineBlob = byteArrayOf(sequence.toByte()),
        preconditionMetaPresent = false,
        preconditionWrittenAt = null,
        preconditionEtag = null,
        advertisedRetiredThroughSequence = 0L,
        generationIdempotencyKey = "generation-$sequence-1",
        preparedAt = 20L + sequence,
        conflictMetaPresent = null,
        conflictWrittenAt = null,
        conflictEtag = null,
        conflictReceivedAt = null,
    )

private fun pruningAck(sequence: Long): MutationAckRecord =
    MutationAckRecord(
        clientId = PRUNING_CLIENT_ID,
        clientSequence = sequence,
        generation = 1,
        authoritativePresence = MutationPresenceState.PRESENT,
        authoritativeBlob = byteArrayOf(sequence.toByte()),
        valueCodecVersion = 1,
        etag = "etag-$sequence",
        canonicalTargetNamespace = null,
        canonicalTargetId = null,
        receivedAt = 40L + sequence,
    )

private fun pruningEffect(
    sequence: Long,
    disposition: MutationEffectDisposition = MutationEffectDisposition.PENDING,
    completedAt: Long? = null,
): MutationEffectRecord =
    MutationEffectRecord(
        clientId = PRUNING_CLIENT_ID,
        clientSequence = sequence,
        effectIndex = 0,
        kind = MutationEffectKind.KEY,
        namespace = "mutations",
        canonicalId = "effect-$sequence",
        createdAt = 30L + sequence,
        disposition = disposition,
        completedAt = completedAt,
    )

private fun pruningAlias(
    sourceId: String = "item-1",
    targetId: String = "canonical-1",
    sequence: Long = 1L,
    state: MutationAliasState = MutationAliasState.PENDING,
    activatedAt: Long? = null,
): MutationKeyAliasRecord =
    MutationKeyAliasRecord(
        sourceNamespace = "mutations",
        sourceCanonicalId = sourceId,
        targetNamespace = "mutations",
        targetCanonicalId = targetId,
        state = state,
        createdByClientId = PRUNING_CLIENT_ID,
        createdBySequence = sequence,
        createdAt = 10L + sequence,
        activatedAt = activatedAt,
    )

private fun pruningTombstone(
    sequence: Long,
    canonicalId: String,
    state: MutationTombstoneState = MutationTombstoneState.PENDING,
    activatedAt: Long? = null,
    supersededBySequence: Long? = null,
    supersededAt: Long? = null,
): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = "mutations",
        canonicalId = canonicalId,
        createdByClientId = PRUNING_CLIENT_ID,
        createdBySequence = sequence,
        state = state,
        createdAt = 10L + sequence,
        activatedAt = activatedAt,
        supersededByClientId = supersededBySequence?.let { PRUNING_CLIENT_ID },
        supersededBySequence = supersededBySequence,
        supersededAt = supersededAt,
    )

private const val PRUNING_CLIENT_ID: String = "pruning-client"
