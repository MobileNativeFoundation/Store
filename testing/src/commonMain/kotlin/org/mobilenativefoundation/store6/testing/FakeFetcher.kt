package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/**
 * Scripted seam [Fetcher]: per-key FIFO [FetcherResult] queues and invocation recording, including
 * the etag the engine passes when it planned a conditional fetch. Unscripted invocations surface
 * as a descriptive [FetcherResult.Error]; override [onUnscripted] to change that.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FakeFetcher<K : StoreKey, V : Any> : Fetcher<K, V> {
    public var onUnscripted: (key: K, etag: String?) -> FetcherResult<V> = { key, _ ->
        FetcherResult.Error(
            IllegalStateException(
                "FakeFetcher.fetch had no scripted result for key " +
                    "${key.namespace.value}/${key.canonicalId()}: enqueue(key, ...) a " +
                    "FetcherResult before the fetch runs or set onUnscripted.",
            ),
        )
    }

    private val scripts =
        MutableStateFlow<Map<Pair<String, String>, List<FetcherResult<V>>>>(emptyMap())
    private val recorded = MutableStateFlow<List<FakeFetcherInvocation>>(emptyList())

    public val invocations: List<FakeFetcherInvocation>
        get() = recorded.value

    public fun clearInvocations() {
        recorded.value = emptyList()
    }

    public fun enqueue(key: K, vararg results: FetcherResult<V>) {
        val id = idOf(key)
        scripts.update { it + (id to (it[id].orEmpty() + results)) }
    }

    override suspend fun fetch(key: K, etag: String?): FetcherResult<V> {
        recorded.update { it + FakeFetcherInvocation(key, etag) }
        return pop(idOf(key)) ?: onUnscripted(key, etag)
    }

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    private fun pop(id: Pair<String, String>): FetcherResult<V>? {
        while (true) {
            val current = scripts.value
            val queue = current[id].orEmpty()
            val head = queue.firstOrNull() ?: return null
            if (scripts.compareAndSet(current, current + (id to queue.drop(1)))) return head
        }
    }
}

/** One recorded [FakeFetcher.fetch] call, appended in invocation order. */
@ExperimentalStoreApi
public class FakeFetcherInvocation(
    /** The key the engine asked for. */
    public val key: StoreKey,

    /** The conditional-request ETag, or null when the engine planned an unconditional fetch. */
    public val etag: String?,
)
