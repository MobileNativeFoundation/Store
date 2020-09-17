package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.asSourceOfTruth
import com.dropbox.android.external.store4.testutil.getData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
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
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )

            // should receive data from persister
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.SourceOfTruth,
                        value = value
                    )
                )

            // clear store entry by key
            store.clear(key)
            assertThat(persister.peekEntry(key))
                .isNull()

            // should fetch data from network again
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )
        }

    @Test
    fun `calling clear(key) on store with in-memory cache (no persister) deletes the entry associated with the key from the in-memory cache`() =
        testScope.runBlockingTest {
            val key = "key"
            val value = 1
            val store = StoreBuilder.from<String, Int, Throwable>(
                fetcher = Fetcher.of { value }
            ).scope(testScope).build()

            // should receive data from network first time
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )

            // should receive data from cache
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Cache,
                        value = value
                    )
                )

            // clear store entry by key
            store.clear(key)

            // should fetch data from network again
            assertThat(store.getData(key))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )
        }

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
                .build()

            // get data for both keys
            store.getData(key1)
            store.getData(key2)

            // clear store entry for key1
            store.clear(key1)

            // entry for key1 is gone
            assertThat(persister.peekEntry(key1))
                .isNull()

            // entry for key2 should still exists
            assertThat(persister.peekEntry(key2))
                .isEqualTo(value2)

            // getting data for key1 should hit the network again
            assertThat(store.getData(key1))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )

            // getting data for key2 should not hit the network
            assertThat(store.getData(key2))
                .isEqualTo(
                    Data(
                        origin = ResponseOrigin.Cache,
                        value = value2
                    )
                )
        }
}
