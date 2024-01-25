package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.asSourceOfTruth
import org.mobilenativefoundation.store.store5.util.getData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@FlowPreview
@ExperimentalCoroutinesApi
@ExperimentalStoreApi
class ClearAllStoreTests {

    private val testScope = TestScope()

    private val key1 = "key1"
    private val key2 = "key2"
    private val value1 = 1
    private val value2 = 2

    private lateinit var fetcher: Fetcher<String, Int>

    private lateinit var persister: InMemoryPersister<String, Int>

    @BeforeTest
    fun before() {
        persister = InMemoryPersister()
        fetcher = Fetcher.of { key: String ->
            when (key) {
                key1 -> value1
                key2 -> value2
                else -> throw IllegalStateException("Unknown key")
            }
        }
    }

    @Test
    fun callingClearAllOnStoreWithPersisterAndNoInMemoryCacheDeletesAllEntriesFromThePersister() = testScope.runTest {
        val store = StoreBuilder.from(
            fetcher = fetcher,
            sourceOfTruth = persister.asSourceOfTruth()
        ).scope(testScope)
            .disableCache()
            .build()

        // should receive data from network first time
        val responseOneA = store.getData(key1)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.Fetcher(),
                value = value1
            ),
            responseOneA
        )
        val responseTwoA = store.getData(key2)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.Fetcher(),
                value = value2
            ),
            responseTwoA
        )
        // should receive data from persister
        val responseOneB = store.getData(key1)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.SourceOfTruth,
                value = value1
            ),
            responseOneB
        )
        val responseTwoB = store.getData(key2)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.SourceOfTruth,
                value = value2
            ),
            responseTwoB
        )
        // clear all entries in store
        store.clear()
        assertNull(persister.peekEntry(key1))
        assertNull(persister.peekEntry(key2))

        // should fetch data from network again
        val responseOneC = store.getData(key1)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.Fetcher(),
                value = value1
            ),
            responseOneC
        )

        val responseTwoC = store.getData(key2)
        advanceUntilIdle()
        assertEquals(
            StoreReadResponse.Data(
                origin = StoreReadResponseOrigin.Fetcher(),
                value = value2
            ),
            responseTwoC
        )
    }

    @Test
    fun callingClearAllOnStoreWithInMemoryCacheAndNoPersisterDeletesAllEntriesFromTheInMemoryCache() =
        testScope.runTest {
            val store = StoreBuilder.from(
                fetcher = fetcher
            ).scope(testScope).build()

            // should receive data from network first time
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Fetcher(),
                    value = value1
                ),
                store.getData(key1)
            )
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Fetcher(),
                    value = value2
                ),
                store.getData(key2)
            )

            // should receive data from cache
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Cache,
                    value = value1
                ),
                store.getData(key1)
            )
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Cache,
                    value = value2
                ),
                store.getData(key2)
            )

            // clear all entries in store
            store.clear()

            // should fetch data from network again
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Fetcher(),
                    value = value1
                ),
                store.getData(key1)
            )
            assertEquals(
                StoreReadResponse.Data(
                    origin = StoreReadResponseOrigin.Fetcher(),
                    value = value2
                ),
                store.getData(key2)
            )
        }
}
