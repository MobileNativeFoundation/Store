package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.StalePolicy
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.impl.StoreUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun <V, K> Store<V, K>.addPersister(
        persister: Persister<V, K>,
        stalePolicy: StalePolicy = StalePolicy.UNSPECIFIED
): Store<V, K> = PersisterStore(this, persister, stalePolicy)

internal class PersisterStore<V, K>(
        private val wrappedStore: Store<V, K>,
        private val persister: Persister<V, K>,
        private val stalePolicy: StalePolicy
) : Store<V, K> {

    private val refreshOnStaleScope = CoroutineScope(SupervisorJob())

    override suspend fun get(key: K): V {
        val cachedValue = runCatching { persister.read(key) }.getOrNull()
        return if (cachedValue != null) {
            if (StoreUtil.persisterIsStale<Any, K>(key, persister)) {
                if (stalePolicy == StalePolicy.REFRESH_ON_STALE) {
                    refreshOnStaleScope.launch {
                        runCatching { fresh(key) }
                    }
                    cachedValue
                } else {
                    runCatching { fresh(key) }.getOrDefault(cachedValue)
                }
            } else {
                cachedValue
            }
        } else {
            val newValue = wrappedStore.get(key)
            persister.write(key, newValue)
            return persister.read(key)!!
        }
    }

    override suspend fun fresh(key: K): V {
        val newValue = wrappedStore.fresh(key)
        persister.write(key, newValue)
        return newValue
    }

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override fun clearMemory() {
        wrappedStore.clearMemory()
    }

    override fun clear(key: K) {
        StoreUtil.clearPersister<Any, K>(persister, key)
    }
}
