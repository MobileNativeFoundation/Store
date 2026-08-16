@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.paging

import androidx.paging.InvalidatingPagingSourceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

internal data class PageKey(
    val query: String,
    val cursor: Int?,
    val limit: Int,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("pages:$query")

    override fun canonicalId(): String = "$query+${cursor ?: "first"}+$limit"
}

internal data class Page(
    val items: List<String>,
    val next: Int?,
    val prev: Int?,
)

internal class ScriptedPageFetcher : Fetcher<PageKey, Page> {
    private val scripts =
        MutableStateFlow<Map<PageKey, List<suspend (String?) -> FetcherResult<Page>>>>(emptyMap())
    private val recordedEtags = MutableStateFlow<Map<PageKey, List<String?>>>(emptyMap())

    fun enqueue(
        key: PageKey,
        vararg results: FetcherResult<Page>,
    ) {
        results.forEach { result -> enqueueAction(key) { result } }
    }

    fun enqueueAction(
        key: PageKey,
        result: suspend (etag: String?) -> FetcherResult<Page>,
    ) {
        scripts.update { current -> current + (key to (current[key].orEmpty() + result)) }
    }

    fun fail(
        key: PageKey,
        cause: Throwable,
    ) {
        enqueue(key, FetcherResult.Error(cause))
    }

    fun callCount(key: PageKey): Int = recordedEtags.value[key].orEmpty().size

    fun etags(key: PageKey): List<String?> = recordedEtags.value[key].orEmpty()

    fun clearCalls() {
        recordedEtags.value = emptyMap()
    }

    override suspend fun fetch(
        key: PageKey,
        etag: String?,
    ): FetcherResult<Page> {
        recordedEtags.update { current -> current + (key to (current[key].orEmpty() + etag)) }
        return pop(key)?.invoke(etag)
            ?: FetcherResult.Error(
                IllegalStateException(
                    "No scripted page result for ${key.namespace.value}/${key.canonicalId()}.",
                ),
            )
    }

    private fun pop(key: PageKey): (suspend (String?) -> FetcherResult<Page>)? {
        while (true) {
            val current = scripts.value
            val queue = current[key].orEmpty()
            val head = queue.firstOrNull() ?: return null
            if (scripts.compareAndSet(current, current + (key to queue.drop(1)))) return head
        }
    }
}

internal fun pageStore(fetcher: ScriptedPageFetcher): Store<PageKey, Page> =
    store { fetcher(fetcher) }

internal class TrackingPageStore(
    private val delegate: Store<PageKey, Page>,
) : Store<PageKey, Page> by delegate {
    private val active = MutableStateFlow(0)
    private val starts = MutableStateFlow(0)
    private val completions = MutableStateFlow(0)

    val activeCollectors: Int
        get() = active.value

    val collectorStarts: Int
        get() = starts.value

    val collectorCompletions: Int
        get() = completions.value

    override fun stream(
        key: PageKey,
        freshness: Freshness,
    ): Flow<org.mobilenativefoundation.store6.core.StoreResult<Page>> =
        flow {
            starts.update { it + 1 }
            active.update { it + 1 }
            try {
                delegate.stream(key, freshness).collect { emit(it) }
            } finally {
                active.update { it - 1 }
                completions.update { it + 1 }
            }
        }

    suspend fun awaitActiveCollectors(expected: Int) {
        active.first { it == expected }
    }

    suspend fun awaitCollectorCompletions(expected: Int) {
        completions.first { it == expected }
    }
}

internal fun Store<PageKey, Page>.standardPagingFactory(
    query: String = "query",
    additionalConfiguration: StorePagingBuilder<PageKey, Page, Int, String>.() -> Unit = {},
): InvalidatingPagingSourceFactory<Int, String> =
    pagingSourceFactory {
        pageKey { paginationKey, loadSize -> PageKey(query, paginationKey, loadSize) }
        items { value -> value.items }
        nextKey { _, value -> value.next }
        prevKey { _, value -> value.prev }
        additionalConfiguration()
    }
