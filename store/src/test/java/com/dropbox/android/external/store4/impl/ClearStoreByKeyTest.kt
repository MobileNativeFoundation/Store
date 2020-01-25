package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.util.InMemoryPersister
import com.dropbox.android.external.store4.util.getWithOrigin
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
            val store = StoreBuilder.fromNonFlow<String, Int>(
                fetcher = { value }
            ).scope(testScope)
                .disableCache()
                .nonFlowingPersister(
                    reader = persister::read,
                    writer = persister::write,
                    delete = persister::deleteByKey
                )
                .build()

            // should receive data from network first time
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )

            // should receive data from persister
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Persister,
                        value = value
                    )
                )

            // clear store entry by key
            store.clear(key)
            assertThat(persister.peekEntry(key))
                .isNull()

            // should fetch data from network again
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
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
            val store = StoreBuilder.fromNonFlow<String, Int>(
                fetcher = { value }
            ).scope(testScope).build()

            // should receive data from network first time
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value
                    )
                )

            // should receive data from cache
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Cache,
                        value = value
                    )
                )

            // clear store entry by key
            store.clear(key)

            // should fetch data from network again
            assertThat(store.getWithOrigin(key))
                .isEqualTo(
                    DataWithOrigin(
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
            val store = StoreBuilder.fromNonFlow<String, Int>(
                fetcher = { key ->
                    when (key) {
                        key1 -> value1
                        key2 -> value2
                        else -> throw IllegalStateException("Unknown key")
                    }
                }
            ).scope(testScope)
                .nonFlowingPersister(
                    reader = persister::read,
                    writer = persister::write,
                    delete = persister::deleteByKey
                )
                .build()

            // get data for both keys
            store.getWithOrigin(key1)
            store.getWithOrigin(key2)

            // clear store entry for key1
            store.clear(key1)

            // entry for key1 is gone
            assertThat(persister.peekEntry(key1))
                .isNull()

            // entry for key2 should still exists
            assertThat(persister.peekEntry(key2))
                .isEqualTo(value2)

            // getting data for key1 should hit the network again
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )

            // getting data for key2 should not hit the network
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Cache,
                        value = value2
                    )
                )
        }
}
