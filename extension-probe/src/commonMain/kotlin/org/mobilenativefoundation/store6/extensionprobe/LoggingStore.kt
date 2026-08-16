package org.mobilenativefoundation.store6.extensionprobe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult

// docs:snippet:guides-extending-logging-store
/** A public-API decorator whose `runtime()` is null because it exposes its own affordances. */
@OptIn(DelicateStoreApi::class)
public class LoggingStore<K : StoreKey, V : Any>(
    private val delegate: Store<K, V>,
    private val log: (String) -> Unit,
) : Store<K, V> by delegate {
    override fun stream(
        key: K,
        freshness: Freshness,
    ): Flow<StoreResult<V>> =
        delegate.stream(key, freshness).onEach { result ->
            log(
                when (result) {
                    is StoreResult.Loading -> "loading(${key.canonicalId()})"
                    is StoreResult.Data -> "data(${key.canonicalId()}, ${result.origin})"
                    is StoreResult.Revalidated -> "revalidated(${key.canonicalId()})"
                    is StoreResult.Error -> "error(${key.canonicalId()})"
                },
            )
        }
}
// docs:snippet:end
