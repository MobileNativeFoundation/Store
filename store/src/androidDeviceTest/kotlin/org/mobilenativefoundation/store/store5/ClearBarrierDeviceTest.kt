package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalStoreApi::class)
class ClearBarrierDeviceTest {

    @Test
    fun clearKeyEvictsSourceOfTruthOnDevice() = runTest {
        val storage = mutableMapOf<String, String>()
        val dataFlow = MutableStateFlow<String?>("initial_device_val")
        storage["item"] = "initial_device_val"

        val sot = SourceOfTruth.of<String, String, String>(
            reader = { dataFlow },
            writer = { k, v ->
                storage[k] = v
                dataFlow.value = v
            },
            delete = { k ->
                storage.remove(k)
                dataFlow.value = null
            },
            deleteAll = {
                storage.clear()
                dataFlow.value = null
            }
        )

        val store = StoreBuilder.from<String, String, String>(
            fetcher = Fetcher.of { key -> "fetched_$key" },
            sourceOfTruth = sot
        ).disableCache()
            .build()

        // Read initial data from SourceOfTruth
        val initial = store.stream(StoreReadRequest.cached("item", refresh = false))
            .filterIsInstance<StoreReadResponse.Data<String>>()
            .first()
        assertEquals("initial_device_val", initial.value)

        // Clear key should trigger delete on SOT
        store.clear("item")
        assertNull(storage["item"])
        assertNull(dataFlow.value)
    }

    @Test
    fun clearAllEvictsAllSourceOfTruthEntriesOnDevice() = runTest {
        val storage = mutableMapOf<String, String>()
        val dataFlow = MutableStateFlow<String?>("initial_all")
        storage["k1"] = "initial_all"

        val sot = SourceOfTruth.of<String, String, String>(
            reader = { dataFlow },
            writer = { k, v ->
                storage[k] = v
                dataFlow.value = v
            },
            delete = { k ->
                storage.remove(k)
                dataFlow.value = null
            },
            deleteAll = {
                storage.clear()
                dataFlow.value = null
            }
        )

        val store = StoreBuilder.from<String, String, String>(
            fetcher = Fetcher.of { key -> "fetched_$key" },
            sourceOfTruth = sot
        ).disableCache()
            .build()

        store.clear()
        assertNull(storage["k1"])
        assertNull(dataFlow.value)
    }
}
