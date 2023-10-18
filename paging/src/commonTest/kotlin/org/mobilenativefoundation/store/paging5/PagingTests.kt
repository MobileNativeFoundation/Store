package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.paging5.util.*
import org.mobilenativefoundation.store.store5.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class)
class PagingTests {
    private val testScope = TestScope()
    private val userId = "123"

    @Test
    fun happyPath() = testScope.runTest {
        val api = FakePostApi()
        val db = FakePostDatabase(userId)
        val factory = PostStoreFactory(api = api, db = db)
        val store = factory.create()

        val key1 = PostKey.Cursor("1", 10)
        val key2 = PostKey.Cursor("11", 10)
        val keys = flowOf(key1, key2)

        val stateFlow = store.launchPagingStore(this, keys)
        stateFlow.test {
            val initialState = awaitItem()
            assertIs<StoreReadResponse.Initial>(initialState)
            val loadingState = awaitItem()
            assertIs<StoreReadResponse.Loading>(loadingState)
            val loadedState1 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState1)
            val data1 = loadedState1.value
            assertEquals(10, data1.posts.size)
            val loadedState2 = awaitItem()
            assertIs<StoreReadResponse.Data<PostData.Feed>>(loadedState2)
            val data2 = loadedState2.value
            assertEquals(20, data2.posts.size)
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
}