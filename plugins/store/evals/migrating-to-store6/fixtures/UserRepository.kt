package com.example.app.data

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.fresh

data class User(
    val id: String,
    val name: String,
    val updatedAtMillis: Long,
)

sealed interface UserUiState {
    data object Loading : UserUiState
    data class Loaded(val user: User, val fromCache: Boolean) : UserUiState
    data class Failed(val message: String) : UserUiState
}

interface UserApi {
    suspend fun fetchUser(id: String): User
}

interface UserDao {
    fun observeUser(id: String): Flow<User?>
    suspend fun upsert(user: User)
    suspend fun delete(id: String)
    suspend fun deleteAll()
}

class UserRepository(
    private val api: UserApi,
    private val dao: UserDao,
    private val nowMillis: () -> Long,
) {
    private val store = StoreBuilder
        .from(
            fetcher = Fetcher.of { id: String -> api.fetchUser(id) },
            sourceOfTruth = SourceOfTruth.of(
                reader = { id -> dao.observeUser(id) },
                writer = { _, user -> dao.upsert(user) },
                delete = { id -> dao.delete(id) },
                deleteAll = { dao.deleteAll() },
            ),
        )
        .validator(
            Validator.by { user ->
                nowMillis() - user.updatedAtMillis < 5.minutes.inWholeMilliseconds
            },
        )
        .build()

    /** Screen subscription: serve cached immediately, refresh from network on subscribe. */
    fun observeUser(id: String): Flow<UserUiState> =
        store.stream(StoreReadRequest.cached(key = id, refresh = true)).map { response ->
            when (response) {
                is StoreReadResponse.Initial,
                is StoreReadResponse.Loading,
                -> UserUiState.Loading

                is StoreReadResponse.Data -> UserUiState.Loaded(
                    user = response.value,
                    fromCache = response.origin is StoreReadResponseOrigin.Cache,
                )

                is StoreReadResponse.NoNewData -> UserUiState.Loading

                is StoreReadResponse.Error.Exception -> UserUiState.Failed(
                    response.error.message ?: "Unknown error",
                )

                is StoreReadResponse.Error.Message -> UserUiState.Failed(response.message)

                is StoreReadResponse.Error.Custom<*> -> UserUiState.Failed("Unknown error")
            }
        }

    /** Pull-to-refresh: caller demands a network round trip. */
    suspend fun refreshUser(id: String): User = store.fresh(id)

    /** Sign-out: forget everything we have cached. */
    suspend fun onSignOut() {
        store.clearAll()
    }
}
