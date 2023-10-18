package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mobilenativefoundation.store.paging5.util.*
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadResponse
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class)
class LaunchPagingStoreTests {
    private val testScope = TestScope()

    private val userId = "123"
    private lateinit var api: PostApi
    private lateinit var db: PostDatabase
    private lateinit var store: MutableStore<PostKey, PostData>

    @Before
    fun setup() {
        api = FakePostApi()
        db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api, db)
        store = factory.create()
    }

    @Test
    fun `state transitions from Loading to Loaded Collection for valid Cursor key`() = testScope.runTest {
        val key = PostKey.Cursor("1", 10)
        val keys = flowOf(key)
        val stateFlow = store.launchPagingStore(this, keys)

        stateFlow.test {
            val state1 = awaitItem()
            assertIs<StoreReadResponse.Initial>(state1)
            val state2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(state2)
            val state3 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData>>(state3)
            expectNoEvents()
        }
    }

    @Test
    fun `state transitions appropriately for multiple valid keys emitted in succession`() = testScope.runTest {
        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)
        val keys = flowOf(key1, key2)
        val stateFlow = store.launchPagingStore(this, keys)

        stateFlow.test {
            val state1 = awaitItem()
            assertIs<StoreReadResponse.Initial>(state1)
            val state2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(state2)
            val state3 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData>>(state3)
            expectNoEvents()

            val state4 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData>>(state4)
            val data4 = state4.value
            assertIs<PostData.Feed>(data4)
            assertEquals(20, data4.items.size)

            expectNoEvents()
        }
    }

    @Test
    fun `state remains consistent if the same key is emitted multiple times`() = testScope.runTest {
        val key = PostKey.Cursor("1", 10)
        val keys = flowOf(key, key)
        val stateFlow = store.launchPagingStore(this, keys)

        stateFlow.test {
            val state1 = awaitItem()
            assertIs<StoreReadResponse.Initial>(state1)
            val state2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(state2)
            val state3 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData>>(state3)
            expectNoEvents()
        }
    }
}
