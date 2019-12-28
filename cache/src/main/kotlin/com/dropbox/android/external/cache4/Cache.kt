package com.dropbox.android.external.cache4

interface Cache<in Key, Value> {

    /**
     * Returns the value associated with [key] in this cache, or null if there is no
     * cached value for [key].
     */
    fun get(key: Key): Value?

    /**
     * Associates [value] with [key] in this cache. If the cache previously contained a
     * value associated with [key], the old value is replaced by [value].
     */
    fun put(key: Key, value: Value)

    /**
     * Discards any cached value for key [key].
     */
    fun invalidate(key: Key)

    /**
     * Discards all entries in the cache.
     */
    fun invalidateAll()
}
