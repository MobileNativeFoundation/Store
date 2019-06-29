package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.impl.Store

fun <V, K> Store(f: suspend (K) -> V) =
        Store(object : Fetcher<V, K> {
            override suspend fun fetch(key: K): V = f(key)
        })

fun <V, K> Store(f: Fetcher<V, K>): Store<V, K> = FetcherStore(f)