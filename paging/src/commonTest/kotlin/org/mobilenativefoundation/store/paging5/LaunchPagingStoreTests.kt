package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.util.FakePostApi
import org.mobilenativefoundation.store.paging5.util.FakePostDatabase
import org.mobilenativefoundation.store.paging5.util.PostApi
import org.mobilenativefoundation.store.paging5.util.PostData
import org.mobilenativefoundation.store.paging5.util.PostDatabase
import org.mobilenativefoundation.store.paging5.util.PostJoiner
import org.mobilenativefoundation.store.paging5.util.PostKey
import org.mobilenativefoundation.store.paging5.util.PostKeyFactory
import org.mobilenativefoundation.store.paging5.util.PostStoreFactory
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class LaunchPagingStoreTests {
    private val testScope = TestScope()

    private val userId = "123"
    private lateinit var api: PostApi
    private lateinit var db: PostDatabase
    private lateinit var store: MutableStore<PostKey, PostData>
    private lateinit var joiner: PostJoiner
    private lateinit var keyFactory: PostKeyFactory
    private lateinit var pager: Pager<String, PostKey, PostData.Post>

    @BeforeTest
    fun setup() {
        api = FakePostApi()
        db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api, db)
        store = factory.create()
        joiner = PostJoiner()
        keyFactory = PostKeyFactory()
    }

    @Test
    fun multipleValidKeysEmittedInSuccession() = testScope.runTest {
        pager = Pager.create(this, store, joiner, keyFactory)

        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)

        val stateFlow = pager.state

        stateFlow.test {
            pager.load(key1)
            val initialState = awaitItem()
            assertEquals(0, initialState.items.size)

            val state1 = awaitItem()
            assertEquals(10, state1.items.size)
            assertEquals("1", state1.items[0].postId)

            pager.load(key2)

            val state2 = awaitItem()
            assertEquals(20, state2.items.size)
            assertEquals("1", state2.items[0].postId)
            assertEquals("11", state2.items[10].postId)

            expectNoEvents()
        }
    }

    @Test
    fun sameKeyEmittedMultipleTimes() = testScope.runTest {
        pager = Pager.create(this, store, joiner, keyFactory)

        val key = PostKey.Cursor("1", 10)

        val stateFlow = pager.state

        stateFlow.test {
            pager.load(key)
            val initialState = awaitItem()
            assertEquals(0, initialState.items.size)

            val state1 = awaitItem()
            assertEquals(10, state1.items.size)
            assertEquals("1", state1.items[0].postId)

            pager.load(key)

            expectNoEvents()
        }
    }

    @Test
    fun multipleKeysWithReadsAndWrites() = testScope.runTest {
        val api = FakePostApi()
        val db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api = api, db = db)
        val mutableStore = factory.create()

        pager = Pager.create(this, mutableStore, joiner, keyFactory)

        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)

        val stateFlow = pager.state
        stateFlow.test {
            pager.load(key1)
            val initialState = awaitItem()
            assertEquals(0, initialState.items.size)

            val state1 = awaitItem()
            assertEquals(10, state1.items.size)
            assertEquals("1", state1.items[0].postId)

            pager.load(key2)

            val state2 = awaitItem()
            assertEquals(20, state2.items.size)
            assertEquals("1", state2.items[0].postId)
            assertEquals("11", state2.items[10].postId)

            mutableStore.write(StoreWriteRequest.of(PostKey.Single("2"), PostData.Post("2", "2-modified")))
            advanceUntilIdle()

            val state3 = awaitItem()
            assertEquals(20, state3.items.size)
            assertEquals("1", state3.items[0].postId)
            assertEquals("2-modified", state3.items[1].title)
            assertEquals("11", state3.items[10].postId)
        }
    }
}
