package org.mobilenativefoundation.store6.quickstart

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store

/** Identifies a user by the stable identifier used by the example service. */
private class UserKey(
    /** The user identifier passed to the example service. */
    val id: String,
) : StoreKey {
    /** The namespace shared by user records in the example store. */
    override val namespace: StoreNamespace = StoreNamespace("users")

    /** Returns the service identifier used to distinguish this user from other users. */
    override fun canonicalId(): String = id
}

/** A user record returned by the example service. */
private class User(
    /** The stable identifier assigned to this user. */
    val id: String,

    /** The display name returned by the example service. */
    val name: String,
)

/** Provides deterministic user data for the executable example. */
private object FakeApi {
    /** Returns a user after simulating an asynchronous service call. */
    suspend fun getUser(id: String): User {
        delay(100)
        return User(id, "User $id")
    }
}

/**
 * Runs an end-to-end Store example backed by a deterministic user service.
 *
 * The program observes loading and fetched data for one user, then retrieves a second user
 * directly and closes the store after both operations complete.
 */
public fun main(): Unit =
    runBlocking {
        // docs:snippet:guides-fetchers-quickstart
        val users = store<UserKey, User> {
            fetcher { key -> FakeApi.getUser(key.id) }
        }
        // docs:snippet:end

        users.stream(UserKey("1")).take(2).collect { result ->
            // docs:snippet:migration-store-result-when
            when (result) {
                is StoreResult.Loading -> println("Loading…")
                is StoreResult.Data -> println("Data(name=${result.value.name}, origin=${result.origin})")
                is StoreResult.Revalidated -> println("Revalidated(age=${result.age})")
                is StoreResult.Error -> println("Error(${result.error})")
            }
            // docs:snippet:end
        }
        println("get: ${users.get(UserKey("2")).name}")
        users.close()
    }
