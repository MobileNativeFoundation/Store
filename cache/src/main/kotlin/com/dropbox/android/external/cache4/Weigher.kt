package com.dropbox.android.external.cache4

interface Weigher<K : Any, V : Any> {
    /**
     * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
     * relative to each other.
     *
     * @return the weight of the entry; must be non-negative
     */
    fun weigh(key: K, value: V): Int
}

internal class OneWeigher<K : Any, V : Any> : Weigher<K, V> {
    override fun weigh(key: K, value: V): Int = 1
}
