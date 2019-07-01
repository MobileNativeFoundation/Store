package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.impl.Store

class Store4Builder<V, K>(internal val wrappedStore: Store<V, K>) {
    fun open() = wrappedStore

    inline infix fun <V2> with(initializer: Store4Builder<V, K>.() -> Store4Builder<V2, K>): Store<V2, K> =
            initializer().open()
}