@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.sqldelight

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SqlDelightMutationRestartWalkingTest {
    @Test
    fun offlineEnqueue_newAdapterShowsOverlayBeforeConnectivity_thenConfirms() =
        runTest(timeout = 25.seconds) {
            val harness = freshJournalHarness()
            val sourceOfTruth = RestartSqlSourceOfTruth()
            val key = RestartSqlKey("restart-walking")
            val server = RestartSqlServer()
            lateinit var append: MutatorRef<RestartSqlKey, String, String>
            val registry =
                mutatorRegistry<RestartSqlKey, String> {
                    append =
                        upsert(
                            id = "append",
                            version = 1,
                            codec = RestartSqlStringCodec,
                            stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                        ) { base, suffix ->
                            val value = (base as? MutationPresence.Present)?.value.orEmpty()
                            MutationPresence.Present(value + suffix)
                        }
                }

            try {
                sourceOfTruth.write(key, "base")
                openRestartSqlStore(harness.storage(), sourceOfTruth, registry, server).use { first ->
                    first.mutate(key, append, "+mine")
                    first.drain(key)
                    assertEquals(listOf("push:1"), server.calls)
                    assertEquals(listOf("mutation-1"), first.pending(key).map { it.mutationId })
                }
                val callsBeforeRestart = server.calls.toList()

                openRestartSqlStore(harness.storage(), sourceOfTruth, registry, server).use { reopened ->
                    val emissions = Channel<StoreResult<String>>(Channel.UNLIMITED)
                    val collection =
                        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            reopened.stream(key, Freshness.LocalOnly).collect(emissions::send)
                        }
                    try {
                        val optimistic =
                            emissions.awaitSqlData { data ->
                                data.value == "base+mine" && data.origin == Origin.OVERLAY
                            }
                        assertEquals("base+mine", optimistic.value)
                        assertTrue(!server.online)
                        assertEquals(callsBeforeRestart, server.calls)

                        server.online = true
                        reopened.drain(key)

                        val confirmed =
                            emissions.awaitSqlData { data ->
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
            } finally {
                harness.driver.close()
            }
        }
}

private fun openRestartSqlStore(
    storage: MutationJournalStorage,
    sourceOfTruth: RestartSqlSourceOfTruth,
    registry: org.mobilenativefoundation.store6.mutations.MutatorRegistry<RestartSqlKey, String>,
    server: MutationServer<RestartSqlKey, String>,
): MutationStore<RestartSqlKey, String> =
    mutationStore(
        registry = registry,
        server = server,
        keyResolver = { identity: MutationKeyIdentity ->
            if (identity.namespace == RestartSqlKey.NAMESPACE.value) {
                RestartSqlKey(identity.canonicalId)
            } else {
                null
            }
        },
        valueCodecVersion = 1,
        valueCodec = RestartSqlStringCodec,
    ) {
        fetcher { error("The SQLDelight AC-4 LocalOnly scenario must not fetch") }
        persistence(sourceOfTruth)
        journalStorage(storage)
    }

private class RestartSqlKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = NAMESPACE

    override fun canonicalId(): String = id

    companion object {
        val NAMESPACE: StoreNamespace = StoreNamespace("restart-sql")
    }
}

private object RestartSqlStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

private class RestartSqlSourceOfTruth : SourceOfTruth<RestartSqlKey, String> {
    private val row = MutableStateFlow<String?>(null)

    override fun reader(key: RestartSqlKey): Flow<String?> = row

    override suspend fun write(
        key: RestartSqlKey,
        value: String,
    ) {
        row.value = value
    }

    override suspend fun delete(key: RestartSqlKey) {
        row.value = null
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        if (namespace == RestartSqlKey.NAMESPACE) row.value = null
    }

    override suspend fun deleteAll() {
        row.value = null
    }
}

private class RestartSqlServer : MutationServer<RestartSqlKey, String> {
    var online: Boolean = false
    val calls: MutableList<String> = mutableListOf()

    override suspend fun push(
        request: MutationPush<RestartSqlKey, String>,
    ): MutationAck<RestartSqlKey, String> {
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

private suspend fun ReceiveChannel<StoreResult<String>>.awaitSqlData(
    predicate: (StoreResult.Data<String>) -> Boolean,
): StoreResult.Data<String> {
    while (true) {
        val result = receive()
        if (result is StoreResult.Data && predicate(result)) return result
    }
}

private inline fun <K : StoreKey, V : Any, R> MutationStore<K, V>.use(
    block: (MutationStore<K, V>) -> R,
): R =
    try {
        block(this)
    } finally {
        close()
    }
