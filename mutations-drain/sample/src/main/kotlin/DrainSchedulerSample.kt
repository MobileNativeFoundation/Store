@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.PendingIntent
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.drain.DrainPassCompleted
import org.mobilenativefoundation.store6.mutations.drain.DrainPolicy
import org.mobilenativefoundation.store6.mutations.drain.InProcessDrainScheduler
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import kotlin.time.Duration.Companion.seconds

private const val STORE_NAME: String = "notes"
private const val NOTE_BODY: String = "hello"

private class NoteKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("notes")

    override fun canonicalId(): String = id
}

private object StringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): String {
        require(version == 1) { "Unsupported String version: $version" }
        return bytes.decodeToString()
    }
}

private class SampleBackend : MutationServer<NoteKey, String> {
    private val rows = mutableMapOf<String, String>()
    private val receipts = mutableMapOf<String, MutationAck<NoteKey, String>>()

    var offline: Boolean = false

    fun load(key: NoteKey): String = rows[key.id].orEmpty()

    override suspend fun push(request: MutationPush<NoteKey, String>): MutationAck<NoteKey, String> {
        check(!offline) { "backend is offline" }
        return receipts.getOrPut(request.idempotencyKey) {
            when (val mine = request.mine) {
                is MutationPresence.Present -> {
                    rows[request.identity.canonicalId] = mine.value
                    MutationPresentAck(
                        authoritative = mine.value,
                        etag = null,
                        canonicalKey = null,
                    )
                }
                MutationPresence.Absent -> error("This sample registers no delete mutator.")
            }
        }
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

public fun main(): Unit =
    runBlocking {
        withTimeout(30.seconds) {
            lateinit var setBody: MutatorRef<NoteKey, String, String>
            val registry =
                mutatorRegistry<NoteKey, String> {
                    setBody =
                        mutator(
                            id = "set-body",
                            version = 1,
                            codec = StringCodec,
                            stales = { _, _ ->
                                StaleSet(keys = emptySet(), namespaces = emptySet())
                            },
                        ) { _, body -> MutationPresence.Present(body) }
                }
            val storage = InMemoryMutationJournalStorage()
            val backend = SampleBackend()
            val key = NoteKey("42")

            fun openStore(): MutationStore<NoteKey, String> =
                mutationStore(
                    registry = registry,
                    server = backend,
                    keyResolver = MutationKeyResolver { identity -> NoteKey(identity.canonicalId) },
                    valueCodecVersion = 1,
                    valueCodec = StringCodec,
                ) {
                    fetcher { backend.load(it) }
                    journalStorage(storage)
                }

            backend.offline = true
            val session1 = openStore()
            try {
                val mutationId = session1.mutate(key, setBody, NOTE_BODY)
                val pending = session1.pendingWrites()
                println("1. Session 1: backend offline, mutate")
                println("   mutationId=$mutationId")
                println("   pendingWrites=${formatPending(pending)}")
            } finally {
                session1.close()
            }

            val session2 = openStore()
            val coordinator = mutationDrainCoordinator(InProcessDrainScheduler(this))
            try {
                println("2. Restart: closed store, reopened over the same InMemoryMutationJournalStorage")
                println("   pendingWrites=${formatPending(session2.pendingWrites())}")

                coordinator.register(STORE_NAME, session2, DrainPolicy())
                backend.offline = false
                println("3. Session 2: coordinator + InProcessDrainScheduler + watch; backend online")

                val drained =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.events
                            .filterIsInstance<DrainPassCompleted>()
                            .first { completed -> completed.pendingIntents == 0 }
                    }
                val watchJob = launch { coordinator.watch(STORE_NAME) }
                drained.await()
                watchJob.cancelAndJoin()

                val confirmed = session2.get(key)
                val remaining = session2.pendingWrites()
                check(confirmed == NOTE_BODY)
                check(remaining.isEmpty())
                println("4. Launch pass drained")
                println("   confirmed=$confirmed")
                println("   pendingWrites=${formatPending(remaining)}")
            } finally {
                coordinator.close()
                session2.close()
            }
        }
    }

private fun formatPending(rows: List<PendingIntent>): String {
    if (rows.isEmpty()) return "0"
    return rows.joinToString(prefix = "${rows.size} [", postfix = "]") { row ->
        "${row.canonicalId} state=${row.state} attempt=${row.attempt}"
    }
}
