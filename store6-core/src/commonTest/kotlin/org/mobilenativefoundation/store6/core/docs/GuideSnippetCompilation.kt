@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
@file:Suppress("unused")

package org.mobilenativefoundation.store6.core.docs

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

private class UserKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

private data class User(
    val id: String,
    val name: String,
)

private class UserApi {
    suspend fun getUser(id: String): User = User(id, "User $id")
}

private val api = UserApi()

private val conditionalUserFetcher =
    object : Fetcher<UserKey, User> {
        override suspend fun fetch(key: UserKey, etag: String?): FetcherResult<User> =
            FetcherResult.Success(api.getUser(key.id), etag)
    }

private fun compileFetcherInstallPoints() {
    // docs:snippet:guides-fetchers-install-points
    val plainUsers = store<UserKey, User> {
        fetcher { key -> api.getUser(key.id) }
    }

    val resultUsers = store<UserKey, User> {
        fetcherOfResult { key ->
            FetcherResult.Success(api.getUser(key.id))
        }
    }

    @OptIn(ExperimentalStoreApi::class)
    val conditionalUsers = store<UserKey, User> {
        fetcher(conditionalUserFetcher)
    }
    // docs:snippet:end

    plainUsers.close()
    resultUsers.close()
    conditionalUsers.close()
}
