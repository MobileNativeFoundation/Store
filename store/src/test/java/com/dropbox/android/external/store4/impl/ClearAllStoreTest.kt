package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ExperimentalStoreApi
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
@ExperimentalStoreApi
@RunWith(JUnit4::class)
class ClearAllStoreTest {

    private val testScope = TestCoroutineScope()

    private val key1 = "key1"
    private val key2 = "key2"
    private val value1 = 1
    private val value2 = 2

    private val fetcher: suspend (key: String) -> Int = { key: String ->
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
            val store = StoreBuilder.fromNonFlow(
                fetcher = fetcher
            ).scope(testScope)
                .disableCache()
                .nonFlowingPersister(
                    reader = persister::read,
                    writer = persister::write,
                    deleteAll = persister::deleteAll
                )
                .build()

            // should receive data from network first time
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value2
                    )
                )

            // should receive data from persister
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Persister,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Persister,
                        value = value2
                    )
                )

            // clear all entries in store
            store.clearAll()
            assertThat(persister.peekEntry(key1))
                .isNull()
            assertThat(persister.peekEntry(key2))
                .isNull()

            // should fetch data from network again
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value2
                    )
                )
        }

    @Test
    fun `calling clearAll() on store with in-memory cache (no persister) deletes all entries from the in-memory cache`() =
        testScope.runBlockingTest {
            val store = StoreBuilder.fromNonFlow(
                fetcher = fetcher
            ).scope(testScope).build()

            // should receive data from network first time
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value2
                    )
                )

            // should receive data from cache
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Cache,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Cache,
                        value = value2
                    )
                )

            // clear all entries in store
            store.clearAll()

            // should fetch data from network again
            assertThat(store.getWithOrigin(key1))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value1
                    )
                )
            assertThat(store.getWithOrigin(key2))
                .isEqualTo(
                    DataWithOrigin(
                        origin = ResponseOrigin.Fetcher,
                        value = value2
                    )
                )
        }
}
