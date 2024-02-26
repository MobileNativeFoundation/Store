package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.impl.DefaultPagingSource
import org.mobilenativefoundation.store.paging5.impl.PagingKeyFactory
import org.mobilenativefoundation.store.paging5.impl.defaultPagingStreamProvider
import org.mobilenativefoundation.store.paging5.util.PostApi
import org.mobilenativefoundation.store.paging5.util.PostData
import org.mobilenativefoundation.store.paging5.util.PostDatabase
import org.mobilenativefoundation.store.paging5.util.PostKey
import org.mobilenativefoundation.store.paging5.util.PostStoreFactory
import org.mobilenativefoundation.store.paging5.util.TestPostApi
import org.mobilenativefoundation.store.paging5.util.TestPostDatabase
import org.mobilenativefoundation.store.store5.MutableStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class)
class PostPagingKeyFactory : PagingKeyFactory<String, PostKey.Single, PostData.Post> {
    override fun createKeyFor(data: PostData.Post): PostKey.Single {
        return PostKey.Single(data.postId)
    }
}

@OptIn(ExperimentalStoreApi::class)
class RealPagerTest {
    private val testScope = TestScope()

    private val userId = "123"
    private lateinit var api: PostApi
    private lateinit var db: PostDatabase
    private lateinit var store: MutableStore<PostKey, PostData>
    private lateinit var streamProvider: PagingStreamProvider<String, PostKey.Cursor>
    private lateinit var pagingSource: PagingSource<String, PostKey.Cursor, PostData.Post>
    private lateinit var pager: Pager<String, PostKey.Cursor, PostData.Post>

    @BeforeTest
    fun setup() {
        api = TestPostApi()
        db = TestPostDatabase(userId)
    }

    private fun TestScope.runPagingTest(
        anchorPosition: StateFlow<String?>,
        initialKey: PostKey.Cursor = PostKey.Cursor("1", 10),
        pagingConfig: PagingConfig = PagingConfig(prefetchDistance = 10),
        testBody: suspend TestScope.() -> Unit
    ) = runTest {
        val factory = PostStoreFactory(this, api, db)
        store = factory.create()

        val keyFactory = PostPagingKeyFactory()

        streamProvider = store.defaultPagingStreamProvider(keyFactory)
        pagingSource = DefaultPagingSource(streamProvider)

        pager = Pager.create(
            scope = this,
            initialKey = initialKey,
            pagingConfig = pagingConfig,
            anchorPosition = anchorPosition
        ) {
            pagingSource
        }

        testBody()
    }

    @Test
    fun testMultipleValidKeysWithIncreasingAnchorPosition() {
        val anchorPosition = MutableStateFlow<String?>("1")

        testScope.runPagingTest(anchorPosition) {
            val flow = pager.data

            flow.test {
                val data1 = awaitItem()
                assertEquals(0, data1.items.size)


                val data2 = awaitItem()
                assertEquals(10, data2.items.size)
                data2.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }

                expectNoEvents()

                anchorPosition.value = "11"

                val data3 = awaitItem()
                data3.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }

                expectNoEvents()

                anchorPosition.value = "21"

                val data4 = awaitItem()
                data4.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }

                expectNoEvents()

                anchorPosition.value = "31"

                val data5 = awaitItem()
                data5.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }

                expectNoEvents()
                anchorPosition.value = "41"

                val data6 = awaitItem()
                data6.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                expectNoEvents()
            }
        }
    }

    @Test
    fun testMultipleValidKeysWithNoChangeInAnchorPositionAndPrefetchDistanceOf10() {
        val anchorPosition = MutableStateFlow<String?>(null)

        testScope.runPagingTest(anchorPosition) {
            val flow = pager.data

            flow.test {
                val data1 = awaitItem()
                assertEquals(0, data1.items.size)
                val data2 = awaitItem()
                assertEquals(10, data2.items.size)
                data2.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                expectNoEvents()
            }
        }
    }

    @Test
    fun testMultipleValidKeysWithNoChangeInAnchorPositionAndPrefetchDistanceOf50() {
        val anchorPosition = MutableStateFlow<String?>(null)

        testScope.runPagingTest(anchorPosition, pagingConfig = PagingConfig(prefetchDistance = 50)) {
            val flow = pager.data

            flow.test {
                val data1 = awaitItem()
                assertEquals(0, data1.items.size)
                val data2 = awaitItem()
                assertEquals(10, data2.items.size)
                data2.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                val data3 = awaitItem()
                data3.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                val data4 = awaitItem()
                data4.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                val data5 = awaitItem()
                data5.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                val data6 = awaitItem()
                data6.items.forEachIndexed { index, value ->
                    assertEquals("${index + 1}", value.postId)
                }
                expectNoEvents()
            }
        }
    }
}
