@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.LoadType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class StorePagingSourceTest {
    @Test
    fun refreshLoad_mapsFirstDataFrameToPage() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val value = Page(items = listOf("a", "b"), next = 2, prev = 0)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(value))
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("a", "b"), page.data)
            assertEquals(2, page.nextKey)
            assertEquals(0, page.prevKey)
            assertEquals(1, fetcher.callCount(key))
        } finally {
            store.close()
        }
    }

    @Test
    fun appendLoad_usesPageKeyFromParams() = runTest {
        val refreshKey = PageKey("query", cursor = null, limit = 4)
        val appendKey = PageKey("query", cursor = 2, limit = 4)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(
                appendKey,
                FetcherResult.Success(Page(listOf("page-2"), next = 3, prev = 1)),
            )
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Append(
                        key = 2,
                        loadSize = 4,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("page-2"), page.data)
            assertEquals(1, fetcher.callCount(appendKey))
            assertEquals(0, fetcher.callCount(refreshKey))
        } finally {
            store.close()
        }
    }

    @Test
    fun prependLoad_mapsPrevKey() = runTest {
        val key = PageKey("query", cursor = 2, limit = 5)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(Page(listOf("page-2"), next = 3, prev = 1)))
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Prepend(
                        key = 2,
                        loadSize = 5,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("page-2"), page.data)
            assertEquals(1, page.prevKey)
            assertEquals(3, page.nextKey)
            assertEquals(1, fetcher.callCount(key))
        } finally {
            store.close()
        }
    }

    @Test
    fun appendLoad_terminalEdge_nullNextKey() = runTest {
        val key = PageKey("query", cursor = 3, limit = 2)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(Page(listOf("last"), next = null, prev = 2)))
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Append(
                        key = 3,
                        loadSize = 2,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertNull(page.nextKey)
        } finally {
            store.close()
        }
    }

    @Test
    fun storeError_surfacesAsLoadResultError_neverThrown() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val cause = IllegalStateException("offline")
        val fetcher = ScriptedPageFetcher().apply {
            fail(key, cause)
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory {
                    freshness { Freshness.MustBeFresh }
                }().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val error = assertIs<PagingSource.LoadResult.Error<Int, String>>(result)
            val exception = assertIs<StoreException>(error.throwable)
            assertIs<StoreError.Fetch>(exception.error)
            assertSame(cause, exception.cause)
        } finally {
            store.close()
        }
    }

    @Test
    fun servedStaleError_surfacesAsError() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val resident = Page(listOf("resident"), next = 2, prev = null)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(resident, etag = "e1"))
        }
        val store = pageStore(fetcher)

        try {
            store.get(key, Freshness.MustBeFresh)
            store.invalidate(key)
            fetcher.fail(key, IllegalStateException("offline"))
            val trailingError =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.stream(key, Freshness.CachedOrFetch)
                        .first { it is StoreResult.Error } as StoreResult.Error
                }

            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("resident"), page.data)
            val streamError = trailingError.await()
            assertEquals(true, streamError.servedStale)
            assertIs<StoreError.Fetch>(streamError.error)
        } finally {
            store.close()
        }

        val missingKey = PageKey("missing", cursor = null, limit = 3)
        val missingFetcher = ScriptedPageFetcher()
        val missingStore = pageStore(missingFetcher)

        try {
            val streamError =
                missingStore.stream(missingKey, Freshness.LocalOnly)
                    .first { it is StoreResult.Error } as StoreResult.Error
            assertEquals(false, streamError.servedStale)
            assertIs<StoreError.Missing>(streamError.error)

            val result =
                missingStore.standardPagingFactory(query = "missing") {
                    freshness { Freshness.LocalOnly }
                }().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val error = assertIs<PagingSource.LoadResult.Error<Int, String>>(result)
            val exception = assertIs<StoreException>(error.throwable)
            assertIs<StoreError.Missing>(exception.error)
            assertEquals(0, missingFetcher.callCount(missingKey))
        } finally {
            missingStore.close()
        }
    }

    @Test
    fun revalidatedWithResident_servesResident() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val resident = Page(listOf("resident"), next = 2, prev = null)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(key, FetcherResult.Success(resident, etag = "e1"))
        }
        val store = pageStore(fetcher)

        try {
            store.get(key, Freshness.MustBeFresh)
            fetcher.enqueue(key, FetcherResult.NotModified(etag = "e1"))

            val result =
                store.standardPagingFactory {
                    freshness { Freshness.MustBeFresh }
                }().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("resident"), page.data)
            assertEquals(listOf(null, "e1"), fetcher.etags(key))
        } finally {
            store.close()
        }
    }

    @Test
    fun loadingFrames_areSkipped_notTerminal() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(
                key,
                FetcherResult.Success(Page(listOf("after-loading"), next = null, prev = null)),
            )
        }
        val store = pageStore(fetcher)

        try {
            val result =
                store.standardPagingFactory()().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            val page = assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("after-loading"), page.data)
        } finally {
            store.close()
        }
    }

    @Test
    fun racedInvalidation_returnsInvalid() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val fetcher = ScriptedPageFetcher().apply {
            enqueueAction(key) {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                FetcherResult.Success(Page(listOf("superseded"), next = null, prev = null))
            }
        }
        val store = pageStore(fetcher)

        try {
            val factory = store.standardPagingFactory()
            val pagingSource = factory()
            val load =
                async {
                    pagingSource.load(
                        PagingSource.LoadParams.Refresh(
                            key = null,
                            loadSize = 3,
                            placeholdersEnabled = false,
                        ),
                    )
                }

            fetchStarted.await()
            factory.invalidate()
            releaseFetch.complete(Unit)

            assertIs<PagingSource.LoadResult.Invalid<Int, String>>(load.await())
        } finally {
            store.close()
        }
    }

    @Test
    fun getRefreshKey_defaultAnchorsViaClosestPageToPosition() = runTest {
        val store = pageStore(ScriptedPageFetcher())

        try {
            val state =
                PagingState(
                    pages =
                        listOf(
                            pagingPage(data = listOf("page-1"), prevKey = null, nextKey = 2),
                            pagingPage(data = listOf("page-2"), prevKey = 1, nextKey = 3),
                            pagingPage(data = listOf("page-3"), prevKey = 2, nextKey = null),
                        ),
                    anchorPosition = 1,
                    config = PagingConfig(pageSize = 1),
                    leadingPlaceholderCount = 0,
                )

            assertEquals(1, store.standardPagingFactory()().getRefreshKey(state))
            assertEquals(
                99,
                store.standardPagingFactory {
                    refreshKey { 99 }
                }().getRefreshKey(state),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun placeholderDoors_itemsBeforeAfter_flowThrough() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(
                key,
                FetcherResult.Success(Page(listOf("one", "two"), next = 2, prev = null)),
            )
        }
        val store = pageStore(fetcher)

        try {
            val params =
                PagingSource.LoadParams.Refresh<Int>(
                    key = null,
                    loadSize = 3,
                    placeholdersEnabled = true,
                )
            val custom =
                store.standardPagingFactory {
                    itemsBefore { _, _ -> 10 }
                    itemsAfter { _, _ -> 20 }
                }().load(params)
            val defaults = store.standardPagingFactory()().load(params)

            val customPage = assertIs<PagingSource.LoadResult.Page<Int, String>>(custom)
            assertEquals(10, customPage.itemsBefore)
            assertEquals(20, customPage.itemsAfter)
            val defaultPage = assertIs<PagingSource.LoadResult.Page<Int, String>>(defaults)
            assertEquals(PagingSource.LoadResult.Page.COUNT_UNDEFINED, defaultPage.itemsBefore)
            assertEquals(PagingSource.LoadResult.Page.COUNT_UNDEFINED, defaultPage.itemsAfter)
        } finally {
            store.close()
        }
    }

    @Test
    fun freshnessDoor_mapsLoadTypeToPolicy() = runTest {
        val refreshKey = PageKey("query", cursor = null, limit = 3)
        val appendKey = PageKey("query", cursor = 2, limit = 3)
        val fetcher = ScriptedPageFetcher().apply {
            enqueue(
                appendKey,
                FetcherResult.Success(Page(listOf("resident"), next = 3, prev = 1)),
            )
        }
        val store = pageStore(fetcher)

        try {
            store.get(appendKey, Freshness.MustBeFresh)
            fetcher.clearCalls()
            fetcher.enqueue(
                refreshKey,
                FetcherResult.Success(Page(listOf("refresh"), next = 2, prev = null)),
            )
            val factory =
                store.standardPagingFactory {
                    freshness { loadType ->
                        if (loadType == LoadType.REFRESH) {
                            Freshness.MustBeFresh
                        } else {
                            Freshness.CachedOrFetch
                        }
                    }
                }

            val refresh =
                factory().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )
            val append =
                factory().load(
                    PagingSource.LoadParams.Append(
                        key = 2,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )

            assertEquals(listOf("refresh"), assertPage(refresh).data)
            assertEquals(listOf("resident"), assertPage(append).data)
            assertEquals(1, fetcher.callCount(refreshKey))
            assertEquals(0, fetcher.callCount(appendKey))
        } finally {
            store.close()
        }
    }

    @Test
    fun builderMissingRequiredDoor_failsFast() = runTest {
        val store = pageStore(ScriptedPageFetcher())

        try {
            assertFailsWith<IllegalStateException> {
                store.pagingSourceFactory<PageKey, Page, Int, String> {
                    items { it.items }
                    nextKey { _, value -> value.next }
                }
            }
            assertFailsWith<IllegalStateException> {
                store.pagingSourceFactory<PageKey, Page, Int, String> {
                    pageKey { paginationKey, loadSize -> PageKey("query", paginationKey, loadSize) }
                    nextKey { _, value -> value.next }
                }
            }
            assertFailsWith<IllegalStateException> {
                store.pagingSourceFactory<PageKey, Page, Int, String> {
                    pageKey { paginationKey, loadSize -> PageKey("query", paginationKey, loadSize) }
                    items { it.items }
                }
            }
        } finally {
            store.close()
        }
    }
}

private fun pagingPage(
    data: List<String>,
    prevKey: Int?,
    nextKey: Int?,
): PagingSource.LoadResult.Page<Int, String> =
    PagingSource.LoadResult.Page(
        data = data,
        prevKey = prevKey,
        nextKey = nextKey,
    )

private fun assertPage(
    result: PagingSource.LoadResult<Int, String>,
): PagingSource.LoadResult.Page<Int, String> = assertIs(result)

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
