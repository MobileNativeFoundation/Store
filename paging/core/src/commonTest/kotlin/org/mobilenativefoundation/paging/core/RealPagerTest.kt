package org.mobilenativefoundation.paging.core

import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.paging.core.PagingConfig.InsertionStrategy
import org.mobilenativefoundation.paging.core.utils.timeline.A
import org.mobilenativefoundation.paging.core.utils.timeline.AuthMiddleware
import org.mobilenativefoundation.paging.core.utils.timeline.Backend
import org.mobilenativefoundation.paging.core.utils.timeline.CK
import org.mobilenativefoundation.paging.core.utils.timeline.D
import org.mobilenativefoundation.paging.core.utils.timeline.E
import org.mobilenativefoundation.paging.core.utils.timeline.ErrorLoggingEffect
import org.mobilenativefoundation.paging.core.utils.timeline.Id
import org.mobilenativefoundation.paging.core.utils.timeline.K
import org.mobilenativefoundation.paging.core.utils.timeline.P
import org.mobilenativefoundation.paging.core.utils.timeline.PD
import org.mobilenativefoundation.paging.core.utils.timeline.PK
import org.mobilenativefoundation.paging.core.utils.timeline.SD
import org.mobilenativefoundation.paging.core.utils.timeline.TimelineAction
import org.mobilenativefoundation.paging.core.utils.timeline.TimelineActionReducer
import org.mobilenativefoundation.paging.core.utils.timeline.TimelineError
import org.mobilenativefoundation.paging.core.utils.timeline.TimelineKeyParams
import org.mobilenativefoundation.paging.core.utils.timeline.TimelineStoreFactory
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("TestFunctionName")
@OptIn(ExperimentalStoreApi::class)
class RealPagerTest {
    private val testScope = TestScope()

    private lateinit var backend: Backend
    private lateinit var timelineStoreFactory: TimelineStoreFactory
    private lateinit var timelineStore: MutableStore<PK, PD>

    @BeforeTest
    fun setup() {
        backend = Backend()
        timelineStoreFactory = TimelineStoreFactory(backend.feedService, backend.postService)
        timelineStore = timelineStoreFactory.create()
    }


    private fun TestScope.StandardTestPagerBuilder(
        initialKey: PK,
        anchorPosition: StateFlow<PK>,
        pagingConfig: PagingConfig = PagingConfig(10, prefetchDistance = 50, insertionStrategy = InsertionStrategy.APPEND),
        maxRetries: Int = 3,
        errorHandlingStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.RetryLast(maxRetries),
        timelineActionReducer: TimelineActionReducer? = null,
        middleware: List<Middleware<Id, K, P, D, E, A>> = emptyList(),
    ) = PagerBuilder<Id, K, P, D, E, A>(
        scope = this,
        initialKey = initialKey,
        initialState = PagingState.Initial(initialKey, null),
        anchorPosition = anchorPosition
    )
        .pagingConfig(pagingConfig)

        .mutableStorePagingSource(timelineStore) {
            StorePagingSourceKeyFactory {
                PagingKey(it.id, TimelineKeyParams.Single())
            }
        }

        .defaultReducer {
            errorHandlingStrategy(errorHandlingStrategy)

            timelineActionReducer?.let {
                customActionReducer(it)
            }
        }

        .apply {
            middleware.forEach {
                this.middleware(it)
            }
        }

        .defaultLogger()

    private fun TestScope.StandardTestPager(
        initialKey: PK,
        anchorPosition: StateFlow<PK>,
        pagingConfig: PagingConfig = PagingConfig(10, prefetchDistance = 50, insertionStrategy = InsertionStrategy.APPEND),
        maxRetries: Int = 3,
        errorHandlingStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.RetryLast(maxRetries),
        timelineActionReducer: TimelineActionReducer? = null,
        middleware: List<Middleware<Id, K, P, D, E, A>> = emptyList(),
    ) = StandardTestPagerBuilder(initialKey, anchorPosition, pagingConfig, maxRetries, errorHandlingStrategy, timelineActionReducer, middleware).build()


    private suspend fun TurbineTestContext<PagingState<Id, K, P, D, E>>.verifyPrefetching(
        pageSize: Int,
        prefetchDistance: Int
    ) {
        fun checkRange(data: List<SD>) {
            data.forEachIndexed { index, item ->
                val id = index + 1
                assertEquals(id, item.id)
            }
        }

        val initial = awaitItem()
        assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

        if (prefetchDistance > 0) {
            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            checkRange(idle.data)
            assertEquals(pageSize, idle.data.size)
        }

        var currentPage = 2
        var expectedDataSize = pageSize

        while (expectedDataSize < prefetchDistance) {
            val loadingMore = awaitItem()
            assertIs<PagingState.Data.LoadingMore<Id, K, P, D, E>>(loadingMore)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            checkRange(idle.data)
            expectedDataSize += pageSize
            assertEquals(expectedDataSize, idle.data.size)

            currentPage++
        }
    }

    @Test
    fun testPrefetchingWhenPrefetchDistanceIsGreaterThan0() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 50
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)
        val pager = StandardTestPager(initialKey, anchorPosition, pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND))

        val state = pager.state

        state.test {
            verifyPrefetching(pageSize, prefetchDistance)

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testPrefetchingWhenPrefetchDistanceEquals0() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)
        val pager = StandardTestPager(initialKey, anchorPosition, pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND))

        val state = pager.state

        state.test {
            verifyPrefetching(pageSize, prefetchDistance)

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testUserLoadWhenPrefetchDistanceEquals0() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)
        val pager = StandardTestPager(initialKey, anchorPosition, pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND))

        val state = pager.state

        state.test {
            verifyPrefetching(pageSize, prefetchDistance)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            assertEquals(pageSize, idle.data.size)

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testErrorHandlingStrategyRetryLast() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)
        val maxRetries = 3

        val message = "Failed to load data"
        val throwable = Throwable(message)
        backend.failWith(throwable)

        val pager = StandardTestPager(initialKey, anchorPosition, pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND), maxRetries = maxRetries)

        val state = pager.state

        state.test {
            val initial = awaitItem()
            assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val error = awaitItem()
            assertIs<PagingState.Error.Exception<Id, K, P, D, E>>(error)
            assertEquals(throwable, error.error)

            val retryCount = backend.getRetryCountFor(initialKey)
            assertEquals(maxRetries, retryCount)

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testErrorHandlingStrategyPassThrough() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)


        val message = "Failed to load data"
        val throwable = Throwable(message)
        backend.failWith(throwable)

        val pager = StandardTestPager(
            initialKey,
            anchorPosition,
            pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND),
            errorHandlingStrategy = ErrorHandlingStrategy.PassThrough
        )

        val state = pager.state

        state.test {
            val initial = awaitItem()
            assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val error = awaitItem()
            assertIs<PagingState.Error.Exception<Id, K, P, D, E>>(error)
            assertEquals(throwable, error.error)
            val retryCount = backend.getRetryCountFor(initialKey)
            assertEquals(0, retryCount)

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testCustomActionReducerModifiesState() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)

        val pager = StandardTestPager(
            initialKey,
            anchorPosition,
            pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND),
            errorHandlingStrategy = ErrorHandlingStrategy.PassThrough,
            timelineActionReducer = TimelineActionReducer()
        )

        val state = pager.state

        state.test {
            val initial = awaitItem()
            assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            assertEquals(pageSize, idle.data.size)

            pager.dispatch(PagingAction.User.Custom(TimelineAction.ClearData))

            val modifiedIdle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(modifiedIdle)
            assertTrue(modifiedIdle.data.isEmpty())

            val headers = backend.getHeadersFor(initialKey)
            assertEquals(0, headers.keys.size)

            expectNoEvents()
        }
    }

    @Test
    fun testMiddlewareInterceptsAndModifiesActions() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)

        val authToken = "Bearer token123"
        val authTokenProvider = { authToken }
        val authMiddleware = AuthMiddleware(authTokenProvider)

        val pager = StandardTestPager(
            initialKey,
            anchorPosition,
            pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND),
            errorHandlingStrategy = ErrorHandlingStrategy.PassThrough,
            timelineActionReducer = TimelineActionReducer(),
            middleware = listOf(authMiddleware)
        )

        val state = pager.state

        state.test {
            val initial = awaitItem()
            assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            assertEquals(pageSize, idle.data.size)

            val headers = backend.getHeadersFor(initialKey)

            assertEquals(1, headers.keys.size)
            assertEquals("auth", headers.keys.first())
            assertEquals(authToken, headers.values.first())

            expectNoEvents()
        }

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEffectsAreLaunchedAfterReducingState() = testScope.runTest {
        val pageSize = 10
        val prefetchDistance = 0
        val initialKey: CK = PagingKey(0, TimelineKeyParams.Collection(pageSize))
        val anchorPosition = MutableStateFlow(initialKey)

        val authToken = "Bearer token123"
        val authTokenProvider = { authToken }
        val authMiddleware = AuthMiddleware(authTokenProvider)

        val message = "Failed to load data"
        val throwable = Throwable(message)

        val errorLoggingEffect = ErrorLoggingEffect {
            when (it) {
                is TimelineError.Exception -> backend.log("Exception", it.throwable.message ?: "")
            }
        }

        val pager = StandardTestPagerBuilder(
            initialKey,
            anchorPosition,
            pagingConfig = PagingConfig(pageSize, prefetchDistance, InsertionStrategy.APPEND),
            errorHandlingStrategy = ErrorHandlingStrategy.PassThrough,
            timelineActionReducer = TimelineActionReducer(),
            middleware = listOf(authMiddleware)
        ).effect(PagingAction.UpdateError::class, PagingState.Error.Exception::class, errorLoggingEffect).build()

        val state = pager.state

        state.test {
            val initial = awaitItem()
            assertIs<PagingState.Initial<Id, K, P, D, E>>(initial)

            backend.failWith(throwable)

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading)

            val error = awaitItem()
            assertIs<PagingState.Error<Id, K, P, D, E, E>>(error)

            assertEquals(1, backend.getLogs().size)
            assertEquals(message, backend.getLogs().first().message)

            backend.clearError()

            pager.dispatch(PagingAction.User.Load(initialKey))

            val loading2 = awaitItem()
            assertIs<PagingState.Loading<Id, K, P, D, E>>(loading2)

            val idle = awaitItem()
            assertIs<PagingState.Data.Idle<Id, K, P, D, E>>(idle)
            assertEquals(pageSize, idle.data.size)

            assertEquals(1, backend.getLogs().size)

            val nextKey = idle.nextKey
            assertNotNull(nextKey)

            backend.failWith(throwable)

            advanceUntilIdle()

            pager.dispatch(PagingAction.User.Load(nextKey))

            val loadingMore = awaitItem()
            assertIs<PagingState.Data.LoadingMore<Id, K, P, D, E>>(loadingMore)

            val error2 = awaitItem()
            assertIs<PagingState.Data.ErrorLoadingMore<Id, K, P, D, E, E>>(error2)

            // The effect is configured to run for PagingState.Error only, not also PagingState.Data.ErrorLoadingMore
            assertEquals(1, backend.getLogs().size)

            expectNoEvents()
        }
    }
}