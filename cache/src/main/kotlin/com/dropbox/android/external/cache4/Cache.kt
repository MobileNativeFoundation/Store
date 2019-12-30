package com.dropbox.android.external.cache4

interface Cache<in Key, Value> {

    /**
     * Returns the value associated with [key] in this cache, or null if there is no
     * cached value for [key].
     */
    fun get(key: Key): Value?

    /**
     * Returns the value associated with [key] in this cache if exists,
     * otherwise gets the value by invoking [loader], associates the value with [key] in the cache,
     * and returns the cached value.
     *
     * Note that if an unexpired value for the [key] is present by the time the [loader] returns
     * the new value, the existing value won't be replaced by the new value. Instead the existing
     * value will be returned.
     */
    fun get(key: Key, loader: () -> Value): Value

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
