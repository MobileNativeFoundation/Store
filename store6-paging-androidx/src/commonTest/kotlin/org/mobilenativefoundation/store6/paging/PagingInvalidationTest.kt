@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.paging

import androidx.paging.PagingSource
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class PagingInvalidationTest {
    @Test
    fun invalidateKey_drivesGenerationInvalidation() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetcher =
            ScriptedPageFetcher().apply {
                enqueue(
                    key,
                    FetcherResult.Success(Page(listOf("v1"), next = null, prev = null)),
                    FetcherResult.Success(Page(listOf("v2"), next = null, prev = null)),
                )
            }
        val store = TrackingPageStore(pageStore(fetcher))
        val factory = store.maxAgePagingFactory()

        try {
            val generationA = factory()
            val invalidated = generationA.invalidationProbe()

            assertEquals(listOf("v1"), generationA.refresh().data)
            assertEquals(1, store.activeCollectors)

            store.invalidate(key)
            invalidated.awaitCount(1)

            val generationB = factory()
            assertEquals(listOf("v2"), generationB.refresh().data)
            assertFalse(generationB.invalid)
            assertEquals(2, fetcher.callCount(key))
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }

    @Test
    fun namespaceWatermark_coversNeverFetchedPages() = runTest {
        val page0 = PageKey("query", cursor = null, limit = 1)
        val page1 = PageKey("query", cursor = 1, limit = 1)
        val fetcher =
            ScriptedPageFetcher().apply {
                enqueue(
                    page0,
                    FetcherResult.Success(Page(listOf("page-0"), next = 1, prev = null)),
                    FetcherResult.Success(Page(listOf("page-0-refetched"), next = 1, prev = null)),
                )
                enqueue(
                    page1,
                    FetcherResult.Success(Page(listOf("page-1"), next = null, prev = 0)),
                )
            }
        val store = TrackingPageStore(pageStore(fetcher))
        val factory = store.maxAgePagingFactory()

        try {
            val generationA = factory()
            val invalidated = generationA.invalidationProbe()

            assertEquals(listOf("page-0"), generationA.refresh(loadSize = 1).data)
            assertEquals(0, fetcher.callCount(page1))

            store.invalidateNamespace(page0.namespace)
            invalidated.awaitCount(1)

            val generationB = factory()
            val neverFetchedPage = generationB.append(key = 1, loadSize = 1)
            assertEquals(listOf("page-1"), neverFetchedPage.data)
            assertFalse(generationB.invalid)
            assertEquals(1, fetcher.callCount(page1))
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }

    @Test
    fun clear_absentTransition_invalidates() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetcher =
            ScriptedPageFetcher().apply {
                enqueue(
                    key,
                    FetcherResult.Success(Page(listOf("before-clear"), next = null, prev = null)),
                    FetcherResult.Success(Page(listOf("after-clear"), next = null, prev = null)),
                )
            }
        val store = TrackingPageStore(pageStore(fetcher))
        val factory = store.maxAgePagingFactory()

        try {
            val generationA = factory()
            val invalidated = generationA.invalidationProbe()
            assertEquals(listOf("before-clear"), generationA.refresh().data)

            store.clear(key)
            invalidated.awaitCount(1)

            val generationB = factory()
            val afterClear = generationB.refresh()
            assertEquals(listOf("after-clear"), afterClear.data)
            assertFalse(generationB.invalid)
            assertEquals(2, fetcher.callCount(key))
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }

    @Test
    fun staleResident_generationSequence_convergesWithoutLoop() = runTest {
        val stale = Page(listOf("same-value"), next = null, prev = null)
        val fresh = Page(listOf("same-value"), next = null, prev = null)
        val store =
            FramePageStore(
                TestStoreResults.data(
                    stale,
                    origin = Origin.MEMORY,
                    isStale = true,
                    refreshing = true,
                ),
            )
        val factory = store.standardPagingFactory()

        try {
            val generationA = factory()
            val invalidatedA = generationA.invalidationProbe()
            assertEquals(listOf("same-value"), generationA.refresh().data)
            store.awaitDeliveries(1)

            store.emit(TestStoreResults.revalidated())
            store.awaitDeliveries(2)
            assertEquals(0, invalidatedA.count)

            store.emit(TestStoreResults.error(TestStoreResults.fetchError("offline")))
            store.awaitDeliveries(3)
            assertEquals(0, invalidatedA.count)

            store.emit(TestStoreResults.data(fresh, origin = Origin.FETCHER))
            invalidatedA.awaitCount(1)
            assertEquals(1, invalidatedA.count)

            val generationB = factory()
            val invalidatedB = generationB.invalidationProbe()
            assertEquals(listOf("same-value"), generationB.refresh().data)
            store.awaitActiveCollectors(1)
            assertFalse(generationB.invalid)

            val deliveredBeforeQuietWindow = store.deliveries
            store.emit(TestStoreResults.revalidated())
            store.awaitDeliveries(deliveredBeforeQuietWindow + 1)
            store.emit(TestStoreResults.error(TestStoreResults.fetchError("still offline")))
            store.awaitDeliveries(deliveredBeforeQuietWindow + 2)
            assertEquals(0, invalidatedB.count)
            assertFalse(generationB.invalid)
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }

    @Test
    fun revalidated_neverInvalidates() = runTest {
        val resident = Page(listOf("resident"), next = null, prev = null)
        val store = FramePageStore(TestStoreResults.data(resident))
        val factory = store.standardPagingFactory()

        try {
            val generation = factory()
            val invalidated = generation.invalidationProbe()
            assertEquals(listOf("resident"), generation.refresh().data)
            store.awaitDeliveries(1)

            store.emit(TestStoreResults.revalidated())
            store.awaitDeliveries(2)
            store.emit(TestStoreResults.error(TestStoreResults.fetchError("post-revalidation")))
            store.awaitDeliveries(3)

            assertEquals(0, invalidated.count)
            assertFalse(generation.invalid)
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }

    @Test
    fun watcherScope_cancelledOnInvalidate_noLeak() = runTest {
        val key = PageKey("query", cursor = null, limit = 3)
        val fetcher =
            ScriptedPageFetcher().apply {
                enqueue(key, FetcherResult.Success(Page(listOf("value"), null, null)))
            }
        val store = TrackingPageStore(pageStore(fetcher))
        val factory = store.maxAgePagingFactory()
        val generation = factory()
        val invalidated = generation.invalidationProbe()

        assertEquals(listOf("value"), generation.refresh().data)
        assertEquals(1, store.activeCollectors)

        generation.invalidate()
        invalidated.awaitCount(1)
        store.awaitCollectorCompletions(1)
        assertEquals(0, store.activeCollectors)
        assertEquals(1, store.collectorStarts)
        assertEquals(1, store.collectorCompletions)

        store.close()
    }

    @Test
    fun factoryInvalidate_tearsDownWatcher_once() = runTest {
        val baseline = Page(listOf("baseline"), next = null, prev = null)
        val store = FramePageStore(TestStoreResults.data(baseline))
        val factory = store.standardPagingFactory()
        val generation = factory()
        val invalidated = generation.invalidationProbe()

        try {
            assertEquals(listOf("baseline"), generation.refresh().data)
            store.awaitDeliveries(1)
            assertEquals(1, store.activeCollectors)

            val release = CompletableDeferred<Unit>()
            val operations =
                listOf(
                    async(start = CoroutineStart.UNDISPATCHED) {
                        release.await()
                        store.emit(TestStoreResults.data(Page(listOf("trigger"), null, null)))
                    },
                    async(start = CoroutineStart.UNDISPATCHED) {
                        release.await()
                        factory.invalidate()
                    },
                )
            release.complete(Unit)
            operations.awaitAll()
            invalidated.awaitCount(1)
            store.awaitCollectorCompletions(1)

            assertEquals(1, invalidated.count)
            assertEquals(0, store.activeCollectors)
            assertEquals(1, store.collectorStarts)
            assertEquals(1, store.collectorCompletions)
        } finally {
            store.close()
        }
    }

    @Test
    fun closedRealStore_failureIsShared_withoutRetry() = runTest {
        val delegate = pageStore(ScriptedPageFetcher())
        val store = ClosedStoreProbe(delegate)
        val factory = store.standardPagingFactory()
        val source = factory()
        val params =
            PagingSource.LoadParams.Refresh<Int>(
                key = null,
                loadSize = 3,
                placeholdersEnabled = false,
            )

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { source.load(params) }
            store.awaitCollectorStarts(1)
            val second = async(start = CoroutineStart.UNDISPATCHED) { source.load(params) }

            delegate.close()
            store.releaseFirstCollector()

            val firstError = assertIs<PagingSource.LoadResult.Error<Int, String>>(first.await())
            val secondError = assertIs<PagingSource.LoadResult.Error<Int, String>>(second.await())
            assertIs<IllegalStateException>(firstError.throwable)
            assertEquals("Store is closed.", firstError.throwable.message)
            assertSame(firstError.throwable, secondError.throwable)
            store.awaitCollectorCompletions(1)
            assertEquals(1, store.collectorStarts)
            assertEquals(1, store.collectorCompletions)
        } finally {
            factory.invalidate()
            store.close()
        }
    }

    @Test
    fun cancelledBaselineLoad_releasesCollector_andAllowsRetry() = runTest {
        val store = FramePageStore(TestStoreResults.loading())
        val factory = store.standardPagingFactory()
        val source = factory()
        val load =
            async(start = CoroutineStart.UNDISPATCHED) {
                source.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 3,
                        placeholdersEnabled = false,
                    ),
                )
            }

        try {
            store.awaitDeliveries(1)
            load.cancelAndJoin()
            store.awaitCollectorCompletions(1)
            assertEquals(0, store.activeCollectors)

            store.emit(TestStoreResults.data(Page(listOf("retry"), null, null)))
            assertEquals(listOf("retry"), source.refresh().data)
            assertEquals(2, store.collectorStarts)
        } finally {
            factory.invalidate()
            store.awaitActiveCollectors(0)
            store.close()
        }
    }
}

private class InvalidationProbe {
    private val counts = MutableStateFlow(0)

    val count: Int
        get() = counts.value

    fun record() {
        counts.update { it + 1 }
    }

    suspend fun awaitCount(expected: Int) {
        counts.first { it == expected }
    }
}

private fun PagingSource<Int, String>.invalidationProbe(): InvalidationProbe =
    InvalidationProbe().also { probe -> registerInvalidatedCallback(probe::record) }

private fun Store<PageKey, Page>.maxAgePagingFactory() =
    standardPagingFactory {
        freshness { Freshness.MaxAge(1.days) }
    }

private suspend fun PagingSource<Int, String>.refresh(
    loadSize: Int = 3,
): PagingSource.LoadResult.Page<Int, String> =
    assertIs(
        load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = loadSize,
                placeholdersEnabled = false,
            ),
        ),
    )

private suspend fun PagingSource<Int, String>.append(
    key: Int,
    loadSize: Int,
): PagingSource.LoadResult.Page<Int, String> =
    assertIs(
        load(
            PagingSource.LoadParams.Append(
                key = key,
                loadSize = loadSize,
                placeholdersEnabled = false,
            ),
        ),
    )

private class FramePageStore(
    initial: StoreResult<Page>,
) : Store<PageKey, Page> {
    private val frame = MutableStateFlow(initial)
    private val active = MutableStateFlow(0)
    private val delivered = MutableStateFlow(0)
    private val starts = MutableStateFlow(0)
    private val completions = MutableStateFlow(0)
    private var closed = false

    val activeCollectors: Int
        get() = active.value

    val deliveries: Int
        get() = delivered.value

    val collectorStarts: Int
        get() = starts.value

    val collectorCompletions: Int
        get() = completions.value

    fun emit(result: StoreResult<Page>) {
        ensureOpen()
        frame.value = result
    }

    suspend fun awaitActiveCollectors(expected: Int) {
        active.first { it == expected }
    }

    suspend fun awaitCollectorCompletions(expected: Int) {
        completions.first { it == expected }
    }

    suspend fun awaitDeliveries(expected: Int) {
        delivered.first { it >= expected }
    }

    override fun stream(
        key: PageKey,
        freshness: Freshness,
    ): Flow<StoreResult<Page>> =
        flow {
            ensureOpen()
            starts.update { it + 1 }
            active.update { it + 1 }
            try {
                frame.collect { result ->
                    emit(result)
                    delivered.update { it + 1 }
                }
            } finally {
                active.update { it - 1 }
                completions.update { it + 1 }
            }
        }

    override suspend fun get(
        key: PageKey,
        freshness: Freshness,
    ): Page {
        ensureOpen()
        return when (val current = frame.value) {
            is StoreResult.Data -> current.value
            is StoreResult.Error -> throw TestStoreResults.exception(current.error)
            is StoreResult.Loading,
            is StoreResult.Revalidated,
            -> throw IllegalStateException("FramePageStore has no resident Data frame.")
        }
    }

    override suspend fun invalidate(key: PageKey) {
        ensureOpen()
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
    }

    override suspend fun invalidateAll() {
        ensureOpen()
    }

    override suspend fun clear(key: PageKey) {
        ensureOpen()
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
    }

    override suspend fun clearAll() {
        ensureOpen()
    }

    override fun close() {
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("FramePageStore is closed.")
    }
}

private class ClosedStoreProbe(
    private val delegate: Store<PageKey, Page>,
) : Store<PageKey, Page> by delegate {
    private val releaseFirst = CompletableDeferred<Unit>()
    private val starts = MutableStateFlow(0)
    private val completions = MutableStateFlow(0)

    val collectorStarts: Int
        get() = starts.value

    val collectorCompletions: Int
        get() = completions.value

    override fun stream(
        key: PageKey,
        freshness: Freshness,
    ): Flow<StoreResult<Page>> =
        flow {
            val attempt = nextAttempt()
            try {
                if (attempt == 1) {
                    releaseFirst.await()
                    delegate.stream(key, freshness).collect { emit(it) }
                } else {
                    emit(TestStoreResults.data(Page(listOf("unexpected retry"), null, null)))
                    awaitCancellation()
                }
            } finally {
                completions.update { it + 1 }
            }
        }

    fun releaseFirstCollector() {
        releaseFirst.complete(Unit)
    }

    suspend fun awaitCollectorStarts(expected: Int) {
        starts.first { it == expected }
    }

    suspend fun awaitCollectorCompletions(expected: Int) {
        completions.first { it == expected }
    }

    private fun nextAttempt(): Int {
        while (true) {
            val current = starts.value
            if (starts.compareAndSet(current, current + 1)) return current + 1
        }
    }
}

private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
