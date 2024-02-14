package org.mobilenativefoundation.store.paging5

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.impl.DefaultLogger
import org.mobilenativefoundation.store.paging5.impl.DefaultPagingSource
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
import kotlin.test.assertIs

sealed class CustomPostAction

sealed class CustomPostError

@OptIn(ExperimentalStoreApi::class)
class RealPagerTest {
    private val testScope = TestScope()

    private val userId = "123"

    private lateinit var api: PostApi
    private lateinit var db: PostDatabase
    private lateinit var store: MutableStore<PostKey, PostData>
    private lateinit var anchorPosition: MutableStateFlow<String>
    private lateinit var pager: Pager<String, PostKey.Cursor, PostData.Post, CustomPostAction, CustomPostError>

    @BeforeTest
    fun setup() {
        api = TestPostApi()
        db = TestPostDatabase(userId)
        val storeFactory = PostStoreFactory(api, db)
        store = storeFactory.create()
        anchorPosition = MutableStateFlow("")

        val initialKey = PostKey.Cursor("1", 10)

        pager = PagerBuilder<String, PostKey.Cursor, PostData.Post, CustomPostAction, CustomPostError>(
            initialKey,
            anchorPosition = anchorPosition,
            pagingConfig = PagingConfig(),
            scope = testScope
        ).dispatcher(DefaultLogger()) {

            defaultReducer {
                errorHandlingStrategy(ErrorHandlingStrategy.PassThrough)
                pagingBufferMaxSize(50)
            }

            defaultPostReducerEffects(
                pagingSource = DefaultPagingSource(
                    streamProvider = store.defaultPagingStreamProvider(
                        keyFactory = object : PagingKeyFactory<String, PostKey.Single, PostData.Post> {
                            override fun createKeyFor(data: PostData.Post): PostKey.Single {
                                return PostKey.Single(data.postId)
                            }
                        }
                    )
                ),
            )
        }.build()
    }

    @Test
    fun testPrefetching() = testScope.runTest {
        val state = pager.state

        state.test {

            val a = awaitItem()
            assertIs<PagingState.LoadingInitial<String, PostKey.Cursor, PostData.Post>>(a)

            val c = awaitItem()
            assertIs<PagingState.Data.Idle<String, PostKey.Cursor, PostData.Post>>(c)

            val d = awaitItem()
            assertIs<PagingState.Data.LoadingMore<String, PostKey.Cursor, PostData.Post>>(d)

            val e = awaitItem()
            assertIs<PagingState.Data.Idle<String, PostKey.Cursor, PostData.Post>>(e)

            val f = awaitItem()
            assertIs<PagingState.Data.LoadingMore<String, PostKey.Cursor, PostData.Post>>(f)

            val g = awaitItem()
            assertIs<PagingState.Data.Idle<String, PostKey.Cursor, PostData.Post>>(g)

            val h = awaitItem()
            assertIs<PagingState.Data.LoadingMore<String, PostKey.Cursor, PostData.Post>>(h)

            val i = awaitItem()
            assertIs<PagingState.Data.Idle<String, PostKey.Cursor, PostData.Post>>(i)

            val j = awaitItem()
            assertIs<PagingState.Data.LoadingMore<String, PostKey.Cursor, PostData.Post>>(j)

            val k = awaitItem()
            assertIs<PagingState.Data.Idle<String, PostKey.Cursor, PostData.Post>>(k)

            expectNoEvents()
        }
    }
}
