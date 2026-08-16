// docs:snippet:mutations-quickstart
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry

private class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}

private data class User(val id: String, val name: String)
private data class Rename(val name: String)

private object RenameCodec : MutationCodec<Rename> {
    override fun encode(value: Rename): ByteArray = value.name.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): Rename {
        require(version == 1) { "Unsupported Rename version: $version" }
        return Rename(bytes.decodeToString())
    }
}

private object UserCodec : MutationCodec<User> {
    override fun encode(value: User): ByteArray =
        "${value.id}\n${value.name}".encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): User {
        require(version == 1) { "Unsupported User version: $version" }
        val fields = bytes.decodeToString().split('\n', limit = 2)
        require(fields.size == 2) { "Malformed User payload" }
        return User(id = fields[0], name = fields[1])
    }
}

private object UserServer : MutationServer<UserKey, User> {
    private val rows = mutableMapOf("42" to User("42", "Ada"))
    private val receipts = mutableMapOf<String, MutationAck<UserKey, User>>()

    fun load(key: UserKey): User = checkNotNull(rows[key.id])

    override suspend fun push(request: MutationPush<UserKey, User>): MutationAck<UserKey, User> =
        receipts.getOrPut(request.idempotencyKey) {
            when (val mine = request.mine) {
                is MutationPresence.Present -> {
                    rows[request.identity.canonicalId] = mine.value
                    MutationPresentAck<UserKey, User>(
                        authoritative = mine.value,
                        etag = null,
                        canonicalKey = null,
                    )
                }
                MutationPresence.Absent -> error("This example registers no delete mutator.")
            }
        }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(
            confirmedThroughSequence = request.retiredThroughSequence,
        )
}

public fun main(): Unit =
    runBlocking {
        lateinit var renameRef: MutatorRef<UserKey, User, Rename>
        val registry =
            mutatorRegistry<UserKey, User> {
                renameRef =
                    update(
                        id = "rename",
                        version = 1,
                        codec = RenameCodec,
                        stales = { _, _ ->
                            StaleSet(keys = emptySet(), namespaces = emptySet())
                        },
                        project = { user, rename -> user.copy(name = rename.name) },
                    )
            }

        val users =
            mutationStore(
                registry = registry,
                server = UserServer,
                keyResolver = MutationKeyResolver { identity -> UserKey(identity.canonicalId) },
                valueCodecVersion = 1,
                valueCodec = UserCodec,
            ) {
                fetcher { key -> UserServer.load(key) }
            }

        try {
            val key = UserKey("42")
            println("base=${users.get(key).name}")

            val mutationId = users.mutate(key, renameRef, Rename("Grace"))
            val optimistic =
                users.stream(key)
                    .filterIsInstance<StoreResult.Data<User>>()
                    .first { data -> data.origin == Origin.OVERLAY }
            println("mutation=$mutationId optimistic=${optimistic.value.name}")

            // get() reads committed truth, so it still returns Ada before drain.
            println("committed-before-drain=${users.get(key).name}")

            users.drain(key)

            // Open a new stream after acknowledgement; an already-active collector is not promised
            // to converge across that boundary yet.
            val confirmed =
                users.stream(key)
                    .filterIsInstance<StoreResult.Data<User>>()
                    .first { data -> data.origin == Origin.SOT || data.origin == Origin.MEMORY }
            println("confirmed=${confirmed.value.name} origin=${confirmed.origin}")
        } finally {
            users.close()
        }
    }
// docs:snippet:end
