package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.StoreReadResponse.Data
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.asSourceOfTruth
import org.mobilenativefoundation.store.store5.util.getData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@FlowPreview
@ExperimentalCoroutinesApi
class ClearStoreByKeyTests {

    private val testScope = TestScope()

    private val persister = InMemoryPersister<String, Int>()

    @Test
    fun callingClearWithKeyOnStoreWithPersisterWithNoInMemoryCacheDeletesTheEntryAssociatedWithTheKeyFromThePersister() = testScope.runTest {
        val key = "key"
        val value = 1
        val store = StoreBuilder.from<String, Int, Int, Int>(
            fetcher = Fetcher.of { value },
            sourceOfTruth = persister.asSourceOfTruth()
        ).scope(testScope)
            .disableCache()
            .build()

        // should receive data from network first time
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Fetcher,
                value = value
            ),
            store.getData(key)
        )

        // should receive data from persister
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.SourceOfTruth,
                value = value
            ),
            store.getData(key)
        )

        // clear store entry by key
        store.clear(key)
        assertNull(persister.peekEntry(key))
        // should fetch data from network again
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Fetcher,
                value = value
            ),
            store.getData(key)
        )
    }

    @Test
    fun callingClearWithKeyOStoreWithInMemoryCacheNoPersisterDeletesTheEntryAssociatedWithTheKeyFromTheInMemoryCache() = testScope.runTest {
        val key = "key"
        val value = 1
        val store = StoreBuilder.from<String, Int, Int>(
            fetcher = Fetcher.of { value }
        ).scope(testScope).build()

        // should receive data from network first time
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Fetcher,
                value = value
            ),
            store.getData(key)
        )

        // should receive data from cache
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Cache,
                value = value
            ),
            store.getData(key)
        )

        // clear store entry by key
        store.clear(key)

        // should fetch data from network again
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Fetcher,
                value = value
            ),
            store.getData(key)
        )
    }

    @Test
    fun callingClearWithKeyOnStoreHasNoEffectOnExistingEntriesAssociatedWithOtherKeysInTheInMemoryCacheOrPersister() = testScope.runTest {
        val key1 = "key1"
        val key2 = "key2"
        val value1 = 1
        val value2 = 2
        val store = StoreBuilder.from<String, Int, Int, Int>(
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
        assertNull(persister.peekEntry(key1))

        // entry for key2 should still exists
        assertEquals(value2, persister.peekEntry(key2))

        // getting data for key1 should hit the network again
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Fetcher,
                value = value1
            ),
            store.getData(key1)
        )

        // getting data for key2 should not hit the network
        assertEquals(
            Data(
                origin = StoreReadResponseOrigin.Cache,
                value = value2
            ),
            store.getData(key2)
        )
    }
}
