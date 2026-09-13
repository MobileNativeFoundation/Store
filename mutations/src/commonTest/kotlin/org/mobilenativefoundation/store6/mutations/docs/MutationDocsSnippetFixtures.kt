@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.docs

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import kotlin.time.Duration

private abstract class AliasCreateSnippet : MutationServer<UserKey, User> {
    // docs:snippet:mutations-alias-provisional-create
    lateinit var createUser: MutatorRef<UserKey, User, NewUser>

    val registry = mutatorRegistry<UserKey, User> {
        createUser =
            create(
                id = "create-user",
                version = 1,
                codec = newUserCodec,
                stales = { _, _ -> StaleSet(keys = emptySet(), namespaces = emptySet()) },
                project = { args -> User(id = args.id, name = args.name) },
            )
    }

    suspend fun createProvisionalUser(
        users: MutationStore<UserKey, User>,
        provisionalKey: UserKey,
    ): String =
        users.mutate(
            key = provisionalKey,
            ref = createUser,
            args = NewUser(id = provisionalKey.canonicalId(), name = "Ada"),
        )

    override suspend fun push(
        request: MutationPush<UserKey, User>,
    ): MutationAck<UserKey, User> {
        val serverUser: User = createOnBackend(request)
        return MutationPresentAck(
            authoritative = serverUser,
            etag = null,
            canonicalKey = UserKey(serverUser.id),
        )
    }
    // docs:snippet:end

    protected abstract suspend fun createOnBackend(request: MutationPush<UserKey, User>): User
}

private fun aliasResolverSnippet() {
    // docs:snippet:mutations-alias-key-resolver
    val keyResolver = MutationKeyResolver<UserKey> { identity ->
        UserKey(identity.canonicalId)
    }
    // docs:snippet:end
}

private suspend fun aliasStreamSnippet(
    users: MutationStore<UserKey, User>,
    provisionalKey: UserKey,
) {
    // docs:snippet:mutations-alias-stream-rekey
    // Keep this one collection across provisional and canonical delegates.
    users.stream(provisionalKey).collect { result ->
        when (result) {
            is StoreResult.Loading -> showLoading()
            is StoreResult.Data ->
                renderUser(
                    user = result.value,
                    saving = result.origin == Origin.OVERLAY,
                )
            is StoreResult.Revalidated -> showRevalidated(result.age)
            // A resolver conversion failure is one value; this collection remains live.
            is StoreResult.Error -> showReadError(result.error)
        }
    }
    // docs:snippet:end
}

private fun drainResolverSnippet(
    knownKeys: Map<Pair<String, String>, UserKey>,
) {
    // docs:snippet:mutations-restart-key-resolvers
    val reconstructible =
        MutationKeyResolver<UserKey> { identity -> UserKey(identity.canonicalId) }

    val lookupBacked =
        MutationKeyResolver<UserKey> { identity ->
            knownKeys[identity.namespace to identity.canonicalId]
        }
    // docs:snippet:end
}

private suspend fun mutationEventsSnippet(
    mutations: MutationStore<UserKey, User>,
) {
    // docs:snippet:mutations-inspection-event-collector
    withTimeoutOrNull(30_000L) {
        mutations.events.collect { event ->
            println(event::class.simpleName ?: "MutationEvent")
        }
    }
    // Dropped events are expected under pressure. Reconcile with durable inspection.
    // docs:snippet:end
}

private class UserKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

private data class User(
    val id: String,
    val name: String,
)

private data class NewUser(
    val id: String,
    val name: String,
)

private object NewUserCodec : MutationCodec<NewUser> {
    override fun encode(value: NewUser): ByteArray =
        "${value.id}\n${value.name}".encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): NewUser {
        require(version == 1)
        val fields = bytes.decodeToString().split('\n', limit = 2)
        return NewUser(id = fields[0], name = fields[1])
    }
}

private val newUserCodec: MutationCodec<NewUser> = NewUserCodec

private fun showLoading() = Unit

private fun renderUser(
    user: User,
    saving: Boolean,
) {
    user.hashCode()
    saving.hashCode()
}

private fun showRevalidated(age: Duration) {
    age.hashCode()
}

private fun showReadError(error: StoreError) {
    error.hashCode()
}
