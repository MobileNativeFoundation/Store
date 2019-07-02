package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.StalePolicy
import com.nytimes.android.external.store3.base.impl.StalePolicy.*
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.impl.StoreUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

fun <V, K> Store4Builder<V, K>.persister(
        persister: Persister<V, K>,
        stalePolicy: StalePolicy = UNSPECIFIED,
        refreshOnStaleScope: CoroutineScope = CoroutineScope(SupervisorJob())
): Store4Builder<V, K> = Store4Builder(PersisterStore(wrappedStore, persister, stalePolicy, refreshOnStaleScope))

internal class PersisterStore<V, K>(
        private val wrappedStore: Store<V, K>,
        private val persister: Persister<V, K>,
        private val stalePolicy: StalePolicy,
        private val refreshOnStaleScope: CoroutineScope
) : Store<V, K> {

    override suspend fun get(key: K): V {
        return try {
            if (StoreUtil.persisterIsStale<Any, K>(key, persister)) {
                when (stalePolicy) {
                    REFRESH_ON_STALE -> {
                        refreshOnStaleScope.launch {
                            try {
                                fresh(key)
                            } catch (e: Exception) {
                                //do nothing
                            }
                        }
                        persister.read(key)!!
                    }
                    NETWORK_BEFORE_STALE -> fresh(key)
                    UNSPECIFIED -> persister.read(key)!!
                }
            } else {
                persister.read(key)!!
            }
        } catch (e: Exception) {
            val newValue = wrappedStore.get(key)
            persister.write(key, newValue)
            return persister.read(key)!!
        }
    }

    override suspend fun fresh(key: K): V {
        val newValue = wrappedStore.fresh(key)
        persister.write(key, newValue)
        return persister.read(key)!!
    }

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override suspend fun clearMemory() {
        wrappedStore.clearMemory()
    }

    override suspend fun clear(key: K) {
        // TODO we should somehow receive it or not make this suspend
        withContext(Dispatchers.IO) {
            StoreUtil.clearPersister<Any, K>(persister, key)
        }
    }
}
