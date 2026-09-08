@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store.store5.mutablestore.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.OnUpdaterCompletion
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store.store5.impl.RealMutableStore

internal class RaceGate {
    val entered = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()

    suspend fun pause() {
        entered.complete(Unit)
        released.await()
    }

    fun open() {
        released.complete(Unit)
    }
}

internal class MutableStoreRaceFixture(
    scope: TestScope,
    post: suspend (String, String) -> UpdaterResult,
    beforeLocalReturn: suspend (String, String) -> Unit = { _, _ -> },
    beforeAcknowledgementCommit: suspend () -> Unit = {},
    val bookkeeper: Bookkeeper<String> = TestInMemoryBookkeeper(),
) {
    val local = MutableStateFlow<Map<String, String>>(emptyMap())
    val posted = mutableListOf<Pair<String, String>>()
    val remote = mutableMapOf<String, String>()
    val successes = mutableListOf<Pair<String, StoreWriteResponse.Success>>()
    val failures = mutableListOf<Pair<String, StoreWriteResponse.Error>>()
    val updaterSuccesses = mutableListOf<UpdaterResult.Success>()
    val updaterFailures = mutableListOf<UpdaterResult.Error>()
    val logger = TestLogger()
    val cache = CacheBuilder<String, String>().build()

    val store = RealMutableStore(
        delegate = testStore<String, String, String, String>(
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            scope = scope.backgroundScope,
            fetcher = Fetcher.of { _: String -> error("Unexpected fetch") },
            sourceOfTruth = SourceOfTruth.of(
                reader = { key: String -> local.map { it[key] } },
                writer = { key: String, value: String ->
                    local.value = local.value + (key to value)
                    beforeLocalReturn(key, value)
                },
            ),
            converter = TestConverter(),
            validator = TestValidator(),
            memoryCache = cache,
        ),
        updater = Updater.by<String, String, String>(
            post = { key, value ->
                posted.add(key to value)
                post(key, value).also { result ->
                    if (result is UpdaterResult.Success) remote[key] = value
                }
            },
            onCompletion = OnUpdaterCompletion(
                onSuccess = { updaterSuccesses.add(it) },
                onFailure = { updaterFailures.add(it) },
            ),
        ),
        bookkeeper = bookkeeper,
        logger = logger,
        beforeAcknowledgementCommit = beforeAcknowledgementCommit,
    )

    fun request(
        value: String,
        created: Long,
        id: String = value,
        key: String = "key",
    ): StoreWriteRequest<String, String, String> = StoreWriteRequest.of(
        key = key,
        value = value,
        created = created,
        onCompletions = listOf(
            OnStoreWriteCompletion(
                onSuccess = { successes.add(id to it) },
                onFailure = { failures.add(id to it) },
            ),
        ),
    )
}
