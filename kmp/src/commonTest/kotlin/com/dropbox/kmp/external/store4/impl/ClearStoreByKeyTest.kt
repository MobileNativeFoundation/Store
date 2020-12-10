package com.dropbox.kmp.external.store4.impl

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

@InternalCoroutinesApi
@FlowPreview
@ExperimentalCoroutinesApi
class ClearStoreByKeyTest {

    private val testScope = TestCoroutineScope()

    private val persister = InMemoryPersister<String, Int>()

    @Test
    fun `calling clear(key) on store with persister (no in-memory cache) deletes the entry associated with the key from the persister`() =
            testScope.runBlockingTest {
                val key = "key"
                val value = 1
                val store = StoreBuilder.from(
                        fetcher = Fetcher.of { value },
                        sourceOfTruth = persister.asSourceOfTruth()
                ).scope(testScope)
                        .disableCache()
                        .build()

                // should receive data from network first time
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value), store.getData(key))

                // should receive data from persister
                assertEquals(Data(origin = ResponseOrigin.SourceOfTruth, value = value), store.getData(key))


                // clear store entry by key
                store.clear(key)
                assertNull(persister.peekEntry(key))

                // should fetch data from network again
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value), store.getData(key))

            }

    // TODO - Implement multiplatform caching
//    @Test
//    fun `calling clear(key) on store with in-memory cache (no persister) deletes the entry associated with the key from the in-memory cache`() =
//        testScope.runBlockingTest {
//            val key = "key"
//            val value = 1
//            val store = StoreBuilder.from<String, Int>(
//                fetcher = Fetcher.of { value }
//            ).scope(testScope).build()
//
//            // should receive data from network first time
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value), store.getData(key))
//
//            // should receive data from cache
//
//            assertThat(store.getData(key))
//                .isEqualTo(
//                    Data(
//                        origin = ResponseOrigin.Cache,
//                        value = value
//                    )
//                )
//
//            // clear store entry by key
//            store.clear(key)
//
//            // should fetch data from network again
//            assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value), store.getData(key))
//
//        }

    @Test
    fun `calling clear(key) on store has no effect on existing entries associated with other keys in the in-memory cache or persister`() =
            testScope.runBlockingTest {
                val key1 = "key1"
                val key2 = "key2"
                val value1 = 1
                val value2 = 2
                val store = StoreBuilder.from(
                        fetcher = Fetcher.of { key ->
                            when (key) {
                                key1 -> value1
                                key2 -> value2
                                else -> throw IllegalStateException("Unknown key")
                            }
                        },
                        sourceOfTruth = persister.asSourceOfTruth()
                ).scope(testScope)
                        .disableCache()
                        .build()

                // get data for both keys
                store.getData(key1)
                store.getData(key2)

                // clear store entry for key1
                store.clear(key1)

                // entry for key1 is gone
                assertNull(persister.peekEntry(key1))

                // entry for key2 should still exists
                assertEquals(value2, persister.peekEntry(key2))

                // getting data for key1 should hit the network again
                assertEquals(Data(origin = ResponseOrigin.Fetcher, value = value1), store.getData(key1))

                // getting data for key2 should not hit the network
                // TODO - Changed to SourceOfTruth, change back to Cache
                // Still verifies ResponseOrigin != Fetcher
                assertEquals(Data(origin = ResponseOrigin.SourceOfTruth, value = value2), store.getData(key2))

            }
}
