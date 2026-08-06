@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationRebaseTest {
    @Test
    fun restartReprojectsPendingFifoOverRefreshedBaseAndNeverAdoptsOldMine() =
        runTest(timeout = 25.seconds) {
            val storage = InMemoryMutationJournalStorage()
            val sourceOfTruth = FakeSourceOfTruth<MutationsTestKey, String>()
            val key = MutationsTestKey("rebase")
            lateinit var append: MutatorRef<MutationsTestKey, String, String>
            val registry =
                mutatorRegistry<MutationsTestKey, String> {
                    append =
                        upsert(
                            id = "append",
                            version = 1,
                            codec = FixtureStringArgsCodec,
                            stales = noStales(),
                        ) { base, suffix ->
                            val current = (base as? MutationPresence.Present)?.value.orEmpty()
                            MutationPresence.Present(current + suffix)
                        }
                }

            sourceOfTruth.write(key, "old")
            val firstServer = RebaseServer(online = false)
            openStore(storage, sourceOfTruth, registry, firstServer).use { first ->
                first.mutate(key, append, "+one")
                first.drain(key)
                first.mutate(key, append, "+two")
            }
            val originalFailedPush = firstServer.pushes.single().snapshot()
            val phasesAfterFailure =
                storage.transaction { transaction ->
                    transaction.executions("client-0").map { execution -> execution.phase }
                }
            assertEquals(
                listOf(
                    org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.READY,
                    org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase.UNPREPARED,
                ),
                phasesAfterFailure,
            )
            val immutableRetry = storage.transaction { it.attempts("client-0").single() }
            sourceOfTruth.write(key, "refreshed")

            val resumedServer =
                RebaseServer(
                    online = true,
                    firstAuthoritative = "authoritative",
                    retirementConfirmationCeiling = 0L,
                )
            openStore(storage, sourceOfTruth, registry, resumedServer).use { reopened ->
                val optimistic =
                    reopened.stream(key, Freshness.LocalOnly).first { result ->
                        result is StoreResult.Data &&
                            result.origin == Origin.OVERLAY &&
                            result.value == "refreshed+one+two"
                    }
                assertEquals("refreshed+one+two", assertIs<StoreResult.Data<String>>(optimistic).value)
                assertEquals(0, resumedServer.pushes.size)

                reopened.drain(key)

                assertEquals(originalFailedPush, resumedServer.pushes.first().snapshot())
                assertEquals(
                    listOf("old", "authoritative"),
                    resumedServer.pushes.map { push ->
                        (push.base as MutationPresence.Present).value
                    },
                )
                assertEquals(
                    listOf("old+one", "authoritative+two"),
                    resumedServer.pushes.map { push ->
                        (push.mine as MutationPresence.Present).value
                    },
                )
                assertEquals("authoritative+two", reopened.get(key, Freshness.LocalOnly))
                assertEquals(emptyList(), reopened.pending(key))
            }

            val attempts = storage.transaction { it.attempts("client-0") }
            assertEquals(2, attempts.size)
            assertContentEquals("old".encodeToByteArray(), attempts[0].baseBlob)
            assertContentEquals("old+one".encodeToByteArray(), attempts[0].mineBlob)
            assertEquals(immutableRetry.generation, attempts[0].generation)
            assertEquals(immutableRetry.generationIdempotencyKey, attempts[0].generationIdempotencyKey)
            assertContentEquals("authoritative".encodeToByteArray(), attempts[1].baseBlob)
            assertContentEquals("authoritative+two".encodeToByteArray(), attempts[1].mineBlob)
        }

    @Test
    fun restartCapturesAllUnpreparedIntentsInFifoOrderOverRefreshedBase() =
        runTest(timeout = 25.seconds) {
            val storage = InMemoryMutationJournalStorage()
            val sourceOfTruth = FakeSourceOfTruth<MutationsTestKey, String>()
            val key = MutationsTestKey("unprepared-rebase")
            lateinit var append: MutatorRef<MutationsTestKey, String, String>
            val registry =
                mutatorRegistry<MutationsTestKey, String> {
                    append =
                        upsert(
                            id = "append-unprepared",
                            version = 1,
                            codec = FixtureStringArgsCodec,
                            stales = noStales(),
                        ) { base, suffix ->
                            val current = (base as? MutationPresence.Present)?.value.orEmpty()
                            MutationPresence.Present(current + suffix)
                        }
                }

            sourceOfTruth.write(key, "old")
            openStore(storage, sourceOfTruth, registry, RebaseServer(online = false)).use { first ->
                first.mutate(key, append, "+one")
                first.mutate(key, append, "+two")
            }
            assertTrue(storage.transaction { it.attempts("client-0").isEmpty() })
            sourceOfTruth.write(key, "refreshed")

            val resumedServer = RebaseServer(online = true, retirementConfirmationCeiling = 0L)
            openStore(storage, sourceOfTruth, registry, resumedServer).use { reopened ->
                val optimistic =
                    reopened.stream(key, Freshness.LocalOnly).first { result ->
                        result is StoreResult.Data &&
                            result.origin == Origin.OVERLAY &&
                            result.value == "refreshed+one+two"
                    }
                assertEquals("refreshed+one+two", assertIs<StoreResult.Data<String>>(optimistic).value)
                assertEquals(0, resumedServer.pushes.size)

                reopened.drain(key)

                assertEquals(
                    listOf("refreshed", "refreshed+one"),
                    resumedServer.pushes.map { push ->
                        (push.base as MutationPresence.Present).value
                    },
                )
                assertEquals(
                    listOf("refreshed+one", "refreshed+one+two"),
                    resumedServer.pushes.map { push ->
                        (push.mine as MutationPresence.Present).value
                    },
                )
                assertEquals("refreshed+one+two", reopened.get(key, Freshness.LocalOnly))
                assertEquals(emptyList(), reopened.pending(key))
            }

            val attempts = storage.transaction { it.attempts("client-0") }
            assertEquals(2, attempts.size)
            assertContentEquals("refreshed".encodeToByteArray(), attempts[0].baseBlob)
            assertContentEquals("refreshed+one".encodeToByteArray(), attempts[0].mineBlob)
            assertContentEquals("refreshed+one".encodeToByteArray(), attempts[1].baseBlob)
            assertContentEquals("refreshed+one+two".encodeToByteArray(), attempts[1].mineBlob)
        }

    private fun openStore(
        storage: InMemoryMutationJournalStorage,
        sourceOfTruth: FakeSourceOfTruth<MutationsTestKey, String>,
        registry: MutatorRegistry<MutationsTestKey, String>,
        server: MutationServer<MutationsTestKey, String>,
    ): MutationStore<MutationsTestKey, String> =
        mutationStore(
            registry = registry,
            server = server,
            keyResolver = MutationsTestKeyResolver,
            valueCodecVersion = 1,
            valueCodec = FixtureStringArgsCodec,
        ) {
            fetcher { error("LocalOnly replay must not fetch") }
            persistence(sourceOfTruth)
            journalStorage(storage)
        }
}

private class RebaseServer(
    private val online: Boolean,
    private val firstAuthoritative: String? = null,
    private val retirementConfirmationCeiling: Long? = null,
) : MutationServer<MutationsTestKey, String> {
    val pushes = mutableListOf<MutationPush<MutationsTestKey, String>>()

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        pushes += request
        check(online) { "server is offline" }
        return when (val mine = request.mine) {
            is MutationPresence.Present ->
                MutationPresentAck(
                    authoritative =
                        if (request.clientSequence == 1L) {
                            firstAuthoritative ?: mine.value
                        } else {
                            mine.value
                        },
                    etag = "etag-${request.clientSequence}",
                    canonicalKey = null,
                )
            MutationPresence.Absent -> MutationAbsentAck(etag = null)
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

private data class RebasePushSnapshot(
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

private fun MutationPush<MutationsTestKey, String>.snapshot(): RebasePushSnapshot =
    RebasePushSnapshot(
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

private inline fun <K : org.mobilenativefoundation.store6.core.StoreKey, V : Any, R>
    MutationStore<K, V>.use(block: (MutationStore<K, V>) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
