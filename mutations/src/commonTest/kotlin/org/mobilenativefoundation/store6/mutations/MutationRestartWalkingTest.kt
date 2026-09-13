@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationRestartWalkingTest {
    @Test
    fun offlineEnqueue_restartShowsOverlayBeforeConnectivity_thenConfirms() =
        runTest(timeout = 25.seconds) {
            val storage = InMemoryMutationJournalStorage()
            val sourceOfTruth = FakeSourceOfTruth<MutationsTestKey, String>()
            val key = MutationsTestKey("restart-walking")
            val server = RestartWalkingServer()
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
                            val value = (base as? MutationPresence.Present)?.value.orEmpty()
                            MutationPresence.Present(value + suffix)
                        }
                }

            sourceOfTruth.write(key, "base")
            openRestartWalkingStore(storage, sourceOfTruth, registry, server).use { first ->
                first.mutate(key, append, "+mine")
                first.drain(key)
                assertEquals(listOf("push:1"), server.calls)
                assertEquals(listOf("mutation-1"), first.pending(key).map(PendingIntent::mutationId))
            }
            val callsBeforeRestart = server.calls.toList()

            openRestartWalkingStore(storage, sourceOfTruth, registry, server).use { reopened ->
                val emissions = Channel<StoreResult<String>>(Channel.UNLIMITED)
                val collection =
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        reopened.stream(key, Freshness.LocalOnly).collect(emissions::send)
                    }
                try {
                    val optimistic =
                        emissions.awaitData { data ->
                            data.value == "base+mine" && data.origin == Origin.OVERLAY
                        }
                    assertEquals("base+mine", optimistic.value)
                    assertTrue(!server.online)
                    assertEquals(callsBeforeRestart, server.calls)

                    server.online = true
                    reopened.drain(key)

                    val confirmed =
                        emissions.awaitData { data ->
                            data.value == "confirmed:base+mine" && data.origin != Origin.OVERLAY
                        }
                    assertEquals("confirmed:base+mine", confirmed.value)
                    assertEquals(2, server.calls.count { call -> call == "push:1" })
                    assertEquals(emptyList(), reopened.pending(key))
                } finally {
                    collection.cancelAndJoin()
                    emissions.cancel()
                }
            }
        }
}

private fun openRestartWalkingStore(
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
        fetcher { error("The AC-4 LocalOnly scenario must not fetch") }
        persistence(sourceOfTruth)
        journalStorage(storage)
    }

private class RestartWalkingServer : MutationServer<MutationsTestKey, String> {
    var online: Boolean = false
    val calls: MutableList<String> = mutableListOf()

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        calls += "push:${request.clientSequence}"
        check(online) { "server is offline" }
        val mine = (request.mine as MutationPresence.Present).value
        return MutationPresentAck(
            authoritative = "confirmed:$mine",
            etag = "etag-${request.clientSequence}",
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck {
        calls += "retire:${request.retiredThroughSequence}"
        check(online) { "server is offline" }
        return MutationRetirementAck(request.retiredThroughSequence)
    }
}

private suspend fun ReceiveChannel<StoreResult<String>>.awaitData(
    predicate: (StoreResult.Data<String>) -> Boolean,
): StoreResult.Data<String> {
    while (true) {
        val result = receive()
        if (result is StoreResult.Data && predicate(result)) return result
    }
}

private inline fun <K : org.mobilenativefoundation.store6.core.StoreKey, V : Any, R>
    MutationStore<K, V>.use(block: (MutationStore<K, V>) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
