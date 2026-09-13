@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.realtime.sample

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.realtime.RealtimeMessage
import org.mobilenativefoundation.store6.realtime.realtimeBinding

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun runSample() {
    val backend = SampleBackend()
    val store =
        store<UserKey, User> {
            fetcher(backend)
        }
    val binding = realtimeBinding(store)
    val key = UserKey("42")

    try {
        val cold = store.get(key)
        check(cold.name == "User 42 v1")
        check(backend.fetches == 1)
        println("Scene 1: cold get; name=${cold.name}; fetches=1")

        val runtime = checkNotNull(store.runtime())
        coroutineScope {
            val sotWrite = CompletableDeferred<Unit>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    runtime.keyEvents.collect { event ->
                        if (event is KeyEvents.Written && event.origin == Origin.SOT) {
                            sotWrite.complete(Unit)
                        }
                    }
                }
            binding.apply(
                RealtimeMessage.Upsert(key, User("42", "User 42 pushed"), etag = "push-1"),
            )
            sotWrite.await()
            collector.cancel()
        }
        check(store.get(key).name == "User 42 pushed")
        check(backend.fetches == 1)
        println("Scene 2: Upsert adopted as SOT; fetches still 1")

        backend.nextName = "User 42 v2"
        binding.apply(RealtimeMessage.Changed(key))
        val refreshed = store.get(key, Freshness.MustBeFresh)
        check(refreshed.name == "User 42 v2")
        check(backend.fetches == 2)
        check(backend.etags.last() == "push-1")
        println("Scene 3: Changed refetched with push etag; fetches=2")

        backend.nextName = "User 42 v3"
        binding.apply(RealtimeMessage.Deleted(key))
        val afterClear = store.get(key, Freshness.MustBeFresh)
        check(afterClear.name == "User 42 v3")
        check(backend.fetches == 3)
        println("Scene 4: Deleted then refetch; fetches=3")

        backend.nextName = "User 42 v4"
        binding.consume(
            flowOf(
                RealtimeMessage.ChangedNamespace(USERS),
                RealtimeMessage.ChangedAll,
            ),
        )
        val afterWatermarks = store.get(key, Freshness.MustBeFresh)
        check(afterWatermarks.name == "User 42 v4")
        check(backend.fetches == 4)
        println("Scene 5: consume namespace then global watermark; fetches=4")
    } finally {
        store.close()
    }
}

private data class UserKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = USERS

    override fun canonicalId(): String = id
}

private data class User(
    val id: String,
    val name: String,
)

private class SampleBackend : Fetcher<UserKey, User> {
    var fetches: Int = 0
        private set
    val etags = mutableListOf<String?>()
    var nextName: String = "User 42 v1"

    override suspend fun fetch(
        key: UserKey,
        etag: String?,
    ): FetcherResult<User> {
        fetches += 1
        etags += etag
        return FetcherResult.Success(User(key.id, nextName), etag = "server-$fetches")
    }
}

private val USERS = StoreNamespace("users")
private const val SAMPLE_TIMEOUT_MILLIS = 20_000L
