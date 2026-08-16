@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.overviewdocs

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.MutatorRegistry
import org.mobilenativefoundation.store6.mutations.mutationStore

private suspend fun overviewSnippet(
    registry: MutatorRegistry<UserKey, User>,
    server: MutationServer<UserKey, User>,
    userJsonCodec: MutationCodec<User>,
    api: UserApi,
    key: UserKey,
    renameRef: MutatorRef<UserKey, User, Rename>,
) {
    // docs:snippet:mutations-overview-build-and-drain
    @OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
    val users = mutationStore(
        registry = registry,
        server = server,
        // Restart-safe key recovery is compile-time required. For keys reconstructible from the
        // identity pair, the resolver is one line:
        keyResolver = MutationKeyResolver { identity -> UserKey(identity.canonicalId) },
        valueCodecVersion = 1,
        valueCodec = userJsonCodec,
    ) {
        fetcher { key -> api.load(key) }
    }

    users.mutate(key, renameRef, Rename("new name"))   // journalled — the only write path
    users.drain(key)                                   // push pending intents and adopt each ack
    // docs:snippet:end

    users.close()
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

private data class Rename(
    val name: String,
)

private fun interface UserApi {
    suspend fun load(key: UserKey): User
}
