package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class)
class PushFailurePolicyDeviceTest {

    @Test
    fun pushFailurePolicyFailFastOnDevice() = runTest {
        val storage = mutableMapOf<String, String>()
        val dataFlow = MutableStateFlow<String?>("local_val")
        storage["key1"] = "local_val"

        val sot = SourceOfTruth.of<String, String, String>(
            reader = { dataFlow },
            writer = { k, v ->
                storage[k] = v
                dataFlow.value = v
            }
        )

        val delegateStore = StoreBuilder.from<String, String, String>(
            fetcher = Fetcher.of { key -> "remote_$key" },
            sourceOfTruth = sot
        ).disableCache()
            .build()

        val updater = Updater.by<String, String, String>(
            post = { key, value ->
                UpdaterResult.Error.Message("Update failed on device")
            }
        )

        val failedKeys = mutableSetOf<String>("key1")
        val bookkeeper = object : Bookkeeper<String> {
            override suspend fun getLastFailedSync(key: String): Long? = if (failedKeys.contains(key)) 12345L else null
            override suspend fun setLastFailedSync(key: String, timestamp: Long): Boolean = true
            override suspend fun clear(key: String): Boolean = failedKeys.remove(key)
            override suspend fun clearAll(): Boolean {
                failedKeys.clear()
                return true
            }
        }

        val failFastStore = delegateStore.asMutableStore<String, String, String, String, String>(
            updater = updater,
            bookkeeper = bookkeeper,
            pushFailurePolicy = PushFailurePolicy.FAIL_FAST
        )

        val responses = failFastStore.stream<String>(
            StoreReadRequest.cached("key1", refresh = true)
        ).toList()

        assertEquals(1, responses.size)
        assertIs<StoreReadResponse.Error.Message>(responses[0])
        assertEquals("Update failed on device", (responses[0] as StoreReadResponse.Error.Message).message)
    }

    @Test
    fun pushFailurePolicySkipFetchKeepLocalOnDevice() = runTest {
        val storage = mutableMapOf<String, String>()
        val dataFlow = MutableStateFlow<String?>("local_val")
        storage["key1"] = "local_val"

        val sot = SourceOfTruth.of<String, String, String>(
            reader = { dataFlow },
            writer = { k, v ->
                storage[k] = v
                dataFlow.value = v
            }
        )

        val delegateStore = StoreBuilder.from<String, String, String>(
            fetcher = Fetcher.of { key -> "remote_$key" },
            sourceOfTruth = sot
        ).disableCache()
            .build()

        val updater = Updater.by<String, String, String>(
            post = { key, value ->
                UpdaterResult.Error.Message("Update failed on device")
            }
        )

        val failedKeys = mutableSetOf<String>("key1")
        val bookkeeper = object : Bookkeeper<String> {
            override suspend fun getLastFailedSync(key: String): Long? = if (failedKeys.contains(key)) 12345L else null
            override suspend fun setLastFailedSync(key: String, timestamp: Long): Boolean = true
            override suspend fun clear(key: String): Boolean = failedKeys.remove(key)
            override suspend fun clearAll(): Boolean {
                failedKeys.clear()
                return true
            }
        }

        val skipFetchStore = delegateStore.asMutableStore<String, String, String, String, String>(
            updater = updater,
            bookkeeper = bookkeeper,
            pushFailurePolicy = PushFailurePolicy.SKIP_FETCH_KEEP_LOCAL
        )

        val responses = skipFetchStore.stream<String>(
            StoreReadRequest.cached("key1", refresh = true)
        ).take(1).toList()

        assertEquals(1, responses.size)
        assertIs<StoreReadResponse.Data<String>>(responses[0])
        assertEquals("local_val", (responses[0] as StoreReadResponse.Data<String>).value)
    }
}
