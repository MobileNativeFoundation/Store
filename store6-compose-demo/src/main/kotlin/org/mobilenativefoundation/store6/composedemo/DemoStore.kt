@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.testing.FakeFetcher

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

data class User(val id: String, val name: String)

/** Live knobs the demo screen mutates while the store keeps fetching. */
class DemoControls {
    val latencyMillis = MutableStateFlow(1500L)
    val failFetches = MutableStateFlow(false)
}

/**
 * Toggleable latency/failure around a store6-testing [FakeFetcher]: scripted results win,
 * otherwise a deterministic versioned user is produced so every refetch visibly changes.
 */
class DemoFetcher(
    private val controls: DemoControls,
    val delegate: FakeFetcher<UserKey, User> = FakeFetcher(),
) : Fetcher<UserKey, User> {
    private var version = 0

    init {
        delegate.onUnscripted = { key, _ ->
            version += 1
            FetcherResult.Success(User(key.id, "User ${key.id} (v$version)"))
        }
    }

    override suspend fun fetch(key: UserKey, etag: String?): FetcherResult<User> {
        delay(controls.latencyMillis.value)
        if (controls.failFetches.value) {
            return FetcherResult.Error(IllegalStateException("Demo failure toggle is on"))
        }
        return delegate.fetch(key, etag)
    }
}
