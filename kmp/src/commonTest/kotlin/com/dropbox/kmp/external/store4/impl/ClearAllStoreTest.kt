package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.ExperimentalStoreApi
import com.dropbox.kmp.external.store4.Fetcher
import com.dropbox.kmp.external.store4.ResponseOrigin
import com.dropbox.kmp.external.store4.StoreBuilder
import com.dropbox.kmp.external.store4.StoreResponse.Data
import com.dropbox.kmp.external.store4.testutil.InMemoryPersister
import com.dropbox.kmp.external.store4.testutil.asSourceOfTruth
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import com.dropbox.kmp.external.store4.testutil.getData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExperimentalStoreApi
@InternalCoroutinesApi
@FlowPreview
@ExperimentalCoroutinesApi
class ClearAllStoreTest {

    private val testScope = TestCoroutineScope()

    private val key1 = "key1"
    private val key2 = "key2"
    private val value1 = 1
    private val value2 = 2

    private val fetcher = Fetcher.of { key: String ->
        when (key) {
            key1 -> value1
            key2 -> value2
            else -> throw IllegalStateException("Unknown key")
        }
    }

    private val persister = InMemoryPersister<String, Int>()

    @Test
    fun `calling clearAll() on store with persister (no in-memory cache) deletes all entries from the persister`() =
            testScope.runBlockingTest {
                val store = StoreBuilder.from(
                        fetcher = fetcher,
                        sourceOfTruth = persister.asSourceOfTruth()
                ).scope(testScope)
                        .disableCache()
                        .build()

                // should receive data from network first time
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value1), store.getData(key1))
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value2), store.getData(key2))
                assertEquals(Data(origin = ResponseOrigin.SourceOfTruth, value = value1), store.getData(key1))
                assertEquals(Data(origin = ResponseOrigin.SourceOfTruth, value = value2), store.getData(key2))


                // clear all entries in store
                store.clearAll()
                assertNull(persister.peekEntry(key1))
                assertNull(persister.peekEntry(key2))

                // should fetch data from network again
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value1), store.getData(key1))
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value2), store.getData(key2))
            }

    // TODO - Implement multiplatform caching

//    @Test
//    fun `calling clearAll() on store with in-memory cache (no persister) deletes all entries from the in-memory cache`() =
//        testScope.runBlockingTest {
//            val store = StoreBuilder.from(
//                fetcher = fetcher
//            ).scope(testScope).build()
//
//            // should receive data from network first time
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value1), store.getData(key1))
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value2), store.getData(key2))
//
//            // should receive data from cache
//             assertEquals(Data(origin = ResponseOrigin.Cache, value = value1), store.getData(key1))
//             assertEquals(Data(origin = ResponseOrigin.Cache, value = value2), store.getData(key2))
//
//            // clear all entries in store
//            store.clearAll()
//
//            // should fetch data from network again
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value1), store.getData(key1))
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value2), store.getData(key2))
//        }
}
