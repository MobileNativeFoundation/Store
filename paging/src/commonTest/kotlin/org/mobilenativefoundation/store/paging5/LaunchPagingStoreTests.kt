package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.util.FakePostApi
import org.mobilenativefoundation.store.paging5.util.FakePostDatabase
import org.mobilenativefoundation.store.paging5.util.PostApi
import org.mobilenativefoundation.store.paging5.util.PostData
import org.mobilenativefoundation.store.paging5.util.PostDataJoiner
import org.mobilenativefoundation.store.paging5.util.PostDatabase
import org.mobilenativefoundation.store.paging5.util.PostKey
import org.mobilenativefoundation.store.paging5.util.PostKeyFactory
import org.mobilenativefoundation.store.paging5.util.PostPutRequestResult
import org.mobilenativefoundation.store.paging5.util.PostStoreFactory
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class)
class LaunchPagingStoreTests {
    private val testScope = TestScope()

    private val userId = "123"
    private lateinit var api: PostApi
    private lateinit var db: PostDatabase
    private lateinit var store: MutableStore<PostKey, PostData>
    private lateinit var joiner: PostDataJoiner
    private val keyFactory = PostKeyFactory()

    @BeforeTest
    fun setup() {
        api = FakePostApi()
        db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api, db)
        store = factory.create()
        joiner = PostDataJoiner()
    }

    @Test
    fun transitionFromInitialToData() = testScope.runTest {
        val key = PostKey.Cursor("1", 10)
        val keys = flowOf(key)
        val stateFlow = store.launchPagingStore(this, keys, joiner, keyFactory = keyFactory)

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
    fun multipleValidKeysEmittedInSuccession() = testScope.runTest {
        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)
        val keys = MutableStateFlow(key1)
        val stateFlow = store.launchPagingStore(this, keys, joiner, keyFactory = keyFactory)

        stateFlow.test {
            val state1 = awaitItem()
            assertIs<StoreReadResponse.Initial>(state1)
            val state2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(state2)
            val state3 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(state3)
            assertEquals("1", state3.value.posts[0].postId)

            keys.emit(key2)

            val loading2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(loading2)

            val state4 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(state4)
            assertEquals("1", state4.value.posts[0].postId)
            assertEquals("11", state4.value.posts[10].postId)
            val data4 = state4.value
            assertIs<PostData.Feed>(data4)
            assertEquals(20, data4.items.size)
            expectNoEvents()
        }
    }

    @Test
    fun sameKeyEmittedMultipleTimes() = testScope.runTest {
        val key = PostKey.Cursor("1", 10)
        val keys = flowOf(key, key)
        val stateFlow = store.launchPagingStore(this, keys, joiner, keyFactory = keyFactory)

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
    fun multipleKeysWithReadsAndWrites() = testScope.runTest {
        val api = FakePostApi()
        val db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api = api, db = db)
        val store = factory.create()

        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)
        val keys = MutableStateFlow(key1)

        val stateFlow = store.launchPagingStore(this, keys, joiner, keyFactory = keyFactory)

        stateFlow.test {
            val initialState = awaitItem()
            assertIs<StoreReadResponse.Initial>(initialState)
            val loadingState = awaitItem()
            assertIs<StoreReadResponse.Loading>(loadingState)
            val loadedState1 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState1)
            val data1 = loadedState1.value
            assertEquals(10, data1.posts.size)
            assertEquals("1", data1.posts[0].postId)
            expectNoEvents()

            keys.emit(key2)

            val loadingState2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(loadingState2)
            val loadedState2 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState2)
            val data2 = loadedState2.value
            println(data2)
            assertEquals(20, data2.posts.size)
            assertEquals("1", data2.posts[0].postId)
        }

        val cached = store.stream<PostPutRequestResult>(StoreReadRequest.cached(key1, refresh = false))
            .first { it.dataOrNull() != null }
        assertIs<StoreReadResponse.Data<PostData>>(cached)
        assertEquals(StoreReadResponseOrigin.Cache, cached.origin)
        val data = cached.requireData()
        assertIs<PostData.Feed>(data)
        assertEquals(10, data.posts.size)

        val cached2 = store.stream<PostPutRequestResult>(StoreReadRequest.cached(PostKey.Single("2"), refresh = false))
            .first { it.dataOrNull() != null }
        assertIs<StoreReadResponse.Data<PostData>>(cached2)
        assertEquals(StoreReadResponseOrigin.Cache, cached2.origin)
        val data2 = cached2.requireData()
        assertIs<PostData.Post>(data2)
        assertEquals("2", data2.title)

        store.write(StoreWriteRequest.of(PostKey.Single("2"), PostData.Post("2", "2-modified")))

        val cached3 = store.stream<PostPutRequestResult>(StoreReadRequest.cached(PostKey.Single("2"), refresh = false))
            .first { it.dataOrNull() != null }
        assertIs<StoreReadResponse.Data<PostData>>(cached3)
        assertEquals(StoreReadResponseOrigin.Cache, cached3.origin)
        val data3 = cached3.requireData()
        assertIs<PostData.Post>(data3)
        assertEquals("2-modified", data3.title)

        val cached4 =
            store.stream<PostPutRequestResult>(StoreReadRequest.cached(PostKey.Cursor("1", 10), refresh = false))
                .first { it.dataOrNull() != null }
        assertIs<StoreReadResponse.Data<PostData>>(cached4)
        assertEquals(StoreReadResponseOrigin.Cache, cached4.origin)
        val data4 = cached4.requireData()
        assertIs<PostData.Feed>(data4)
        assertEquals("2-modified", data4.posts[1].title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun multipleKeysWithReadsAndWritesUsingOneStream() = testScope.runTest {
        val api = FakePostApi()
        val db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api = api, db = db)
        val mutableStore = factory.create()

        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)
        val keys = MutableStateFlow(key1)

        val stateFlow = mutableStore.launchPagingStore(this, keys, joiner, keyFactory = keyFactory)
        stateFlow.test {
            val initialState = awaitItem()
            assertIs<StoreReadResponse.Initial>(initialState)
            val loadingState = awaitItem()
            assertIs<StoreReadResponse.Loading>(loadingState)
            val loadedState1 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState1)
            val data1 = loadedState1.value
            assertEquals(10, data1.posts.size)
            assertEquals("1", data1.posts[0].postId)

            keys.emit(key2)

            val loadingState2 = awaitItem()
            assertIs<StoreReadResponse.Loading>(loadingState2)

            val loadedState2 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState2)
            val data2 = loadedState2.value
            assertEquals(20, data2.posts.size)
            assertEquals("1", data1.posts[0].postId)

            mutableStore.write(StoreWriteRequest.of(PostKey.Single("2"), PostData.Post("2", "2-modified")))
            println("WROTE TO STORE")
            advanceUntilIdle()

            val loadedState3 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState3)
            val data3 = loadedState3.value
            assertEquals(20, data3.posts.size)
            assertEquals("2-modified", data3.posts[1].title) // Actual is "2"
        }
    }
}
