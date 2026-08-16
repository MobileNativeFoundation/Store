@file:OptIn(
    ExperimentalStoreApi::class,
    ExperimentalPagingApi::class,
    DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingConfig
import androidx.paging.Pager
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.LoadType
import androidx.paging.RemoteMediator
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StoreRemoteMediatorTest {
    @Test
    fun mediatorRefresh_invalidatesThenGetsFresh() = runTest {
        val config = PagingConfig(pageSize = 5)
        val key = PageKey("query", cursor = null, limit = config.initialLoadSize)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(Page(listOf("resident"), next = 1, prev = null)))
            enqueue(key, FetcherResult.Success(Page(listOf("fresh"), next = 2, prev = null)))
        }
        val delegate = pageStore(fetcher)

        try {
            delegate.get(key, Freshness.MustBeFresh)
            val store = RecordingPageStore(delegate)
            val pageKeyCalls = mutableListOf<Pair<Int?, Int>>()
            val mediator = pageMediator(store, pageKeyCalls)

            val result = mediator.load(LoadType.REFRESH, pagingState(pageSize = config.pageSize))

            val success = assertIs<RemoteMediator.MediatorResult.Success>(result)
            assertFalse(success.endOfPaginationReached)
            assertEquals(listOf(StoreOperation.INVALIDATE, StoreOperation.GET), store.operations)
            assertEquals(listOf(key), store.invalidatedKeys)
            assertEquals(
                listOf<Pair<PageKey, Freshness>>(key to Freshness.MustBeFresh),
                store.gets,
            )
            assertEquals(
                listOf<Pair<Int?, Int>>(null to config.initialLoadSize),
                pageKeyCalls,
            )
            assertEquals(2, fetcher.callCount(key))
        } finally {
            delegate.close()
        }
    }

    @Test
    fun pagerRefresh_usesSameInitialLoadSizeForMediatorAndPagingSource() = runTest {
        val config = PagingConfig(pageSize = 5, enablePlaceholders = false)
        val initialKey = PageKey("query", cursor = null, limit = config.initialLoadSize)
        val pageSizeKey = PageKey("query", cursor = null, limit = config.pageSize)
        val initialPage =
            Page(
                items = List(config.initialLoadSize) { index -> "item-$index" },
                next = null,
                prev = null,
            )
        val wrongPage =
            Page(
                items = List(config.pageSize) { index -> "wrong-$index" },
                next = null,
                prev = null,
            )
        val fetcher =
            RepeatingPageFetcher(
                mapOf(
                    initialKey to initialPage,
                    pageSizeKey to wrongPage,
                ),
            )
        val delegate = store<PageKey, Page> { fetcher(fetcher) }
        val store = RecordingPageStore(delegate)
        val factory = store.standardPagingFactory()

        try {
            val items =
                Pager(
                    config = config,
                    remoteMediator = pageMediator(store),
                    pagingSourceFactory = { factory() },
                ).flow.asSnapshot()

            assertEquals(initialPage.items, items)
            assertEquals(listOf(initialKey), store.invalidatedKeys)
            assertEquals(
                listOf<Pair<PageKey, Freshness>>(initialKey to Freshness.MustBeFresh),
                store.gets,
            )
        } finally {
            factory.invalidate()
            delegate.close()
        }
    }

    @Test
    fun mediatorAppend_getsCachedOrFetch() = runTest {
        val residentKey = PageKey("query", cursor = 2, limit = 4)
        val coldKey = PageKey("query", cursor = 3, limit = 4)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(
                residentKey,
                FetcherResult.Success(Page(listOf("resident"), next = 3, prev = 1)),
            )
            enqueue(
                coldKey,
                FetcherResult.Success(Page(listOf("cold"), next = null, prev = 2)),
            )
        }
        val delegate = pageStore(fetcher)

        try {
            delegate.get(residentKey, Freshness.MustBeFresh)
            fetcher.clearCalls()
            val store = RecordingPageStore(delegate)
            val mediator = pageMediator(store)

            val residentResult =
                mediator.load(
                    LoadType.APPEND,
                    pagingState(nextKey = 2, pageSize = 4),
                )

            val residentSuccess = assertIs<RemoteMediator.MediatorResult.Success>(residentResult)
            assertFalse(residentSuccess.endOfPaginationReached)
            assertEquals(
                listOf<Pair<PageKey, Freshness>>(residentKey to Freshness.CachedOrFetch),
                store.gets,
            )
            assertEquals(0, fetcher.callCount(residentKey))

            store.clearRecords()
            val coldResult =
                mediator.load(
                    LoadType.APPEND,
                    pagingState(nextKey = 3, pageSize = 4),
                )

            val coldSuccess = assertIs<RemoteMediator.MediatorResult.Success>(coldResult)
            assertTrue(coldSuccess.endOfPaginationReached)
            assertEquals(
                listOf<Pair<PageKey, Freshness>>(coldKey to Freshness.CachedOrFetch),
                store.gets,
            )
            assertEquals(1, fetcher.callCount(coldKey))
        } finally {
            delegate.close()
        }
    }

    @Test
    fun mediatorPrepend_defaultForwardOnly_endOfPagination() = runTest {
        val delegate = pageStore(ScriptedPageFetcher())

        try {
            val store = RecordingPageStore(delegate)
            val pageKeyCalls = mutableListOf<Pair<Int?, Int>>()
            val mediator = pageMediator(store, pageKeyCalls)

            val result =
                mediator.load(
                    LoadType.PREPEND,
                    pagingState(prevKey = null, nextKey = 2, pageSize = 6),
                )

            val success = assertIs<RemoteMediator.MediatorResult.Success>(result)
            assertTrue(success.endOfPaginationReached)
            assertEquals(emptyList(), store.operations)
            assertEquals(emptyList(), pageKeyCalls)
        } finally {
            delegate.close()
        }
    }

    @Test
    fun mediatorEndOfPagination_fromNullNextKey() = runTest {
        val delegate = pageStore(ScriptedPageFetcher())

        try {
            val store = RecordingPageStore(delegate)
            val pageKeyCalls = mutableListOf<Pair<Int?, Int>>()
            val mediator = pageMediator(store, pageKeyCalls)

            val result =
                mediator.load(
                    LoadType.APPEND,
                    pagingState(prevKey = 1, nextKey = null, pageSize = 7),
                )

            val success = assertIs<RemoteMediator.MediatorResult.Success>(result)
            assertTrue(success.endOfPaginationReached)
            assertEquals(emptyList(), store.operations)
            assertEquals(emptyList(), pageKeyCalls)
        } finally {
            delegate.close()
        }
    }

    @Test
    fun mediatorError_fromStoreException() = runTest {
        val key = PageKey("query", cursor = 2, limit = 3)
        val fetcher = ScriptedPageFetcher().apply {
            fail(key, IllegalStateException("offline"))
        }
        val delegate = pageStore(fetcher)

        try {
            val store = RecordingPageStore(delegate)
            val mediator = pageMediator(store)

            val result =
                mediator.load(
                    LoadType.APPEND,
                    pagingState(nextKey = 2, pageSize = 3),
                )

            val error = assertIs<RemoteMediator.MediatorResult.Error>(result)
            val exception = assertIs<StoreException>(error.throwable)
            assertIs<StoreError.Fetch>(exception.error)
        } finally {
            delegate.close()
        }
    }
}

private class RepeatingPageFetcher(
    private val pages: Map<PageKey, Page>,
) : Fetcher<PageKey, Page> {
    override suspend fun fetch(
        key: PageKey,
        etag: String?,
    ): FetcherResult<Page> =
        pages[key]?.let { page -> FetcherResult.Success(page) }
            ?: FetcherResult.Error(
                IllegalStateException(
                    "No repeating page result for ${key.namespace.value}/${key.canonicalId()}.",
                ),
            )
}

private enum class StoreOperation {
    INVALIDATE,
    GET,
}

private class RecordingPageStore(
    private val delegate: Store<PageKey, Page>,
) : Store<PageKey, Page> by delegate {
    val operations = mutableListOf<StoreOperation>()
    val invalidatedKeys = mutableListOf<PageKey>()
    val gets = mutableListOf<Pair<PageKey, Freshness>>()

    override suspend fun invalidate(key: PageKey) {
        operations += StoreOperation.INVALIDATE
        invalidatedKeys += key
        delegate.invalidate(key)
    }

    override suspend fun get(
        key: PageKey,
        freshness: Freshness,
    ): Page {
        operations += StoreOperation.GET
        gets += key to freshness
        return delegate.get(key, freshness)
    }

    fun clearRecords() {
        operations.clear()
        invalidatedKeys.clear()
        gets.clear()
    }
}

private fun pageMediator(
    store: Store<PageKey, Page>,
    pageKeyCalls: MutableList<Pair<Int?, Int>> = mutableListOf(),
): StoreRemoteMediator<PageKey, Page, Int, String> =
    object : StoreRemoteMediator<PageKey, Page, Int, String>(store) {
        override fun pageKey(
            paginationKey: Int?,
            loadSize: Int,
        ): PageKey {
            pageKeyCalls += paginationKey to loadSize
            return PageKey("query", paginationKey, loadSize)
        }

        override fun nextKey(
            key: PageKey,
            value: Page,
        ): Int? = value.next
    }

private fun pagingState(
    prevKey: Int? = null,
    nextKey: Int? = null,
    pageSize: Int,
): PagingState<Int, String> =
    PagingState(
        pages =
            listOf(
                PagingSource.LoadResult.Page(
                    data = listOf("loaded"),
                    prevKey = prevKey,
                    nextKey = nextKey,
                ),
            ),
        anchorPosition = null,
        config = PagingConfig(pageSize = pageSize),
        leadingPlaceholderCount = 0,
    )

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
