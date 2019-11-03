package com.nytimes.android.external.store4

data class StoreRequest<Key> private constructor(
    /**
     * The key for the request
     */
    val key: Key,
    /**
     * List of cache types that should be skipped when retuning the response
     */
    private val skippedCaches: Int,
    /**
     * If set to with stream requests, Store will always get fresh value from fetcher while also
     * starting the stream from the local data (disk and/or memory cache)
     */
    val refresh: Boolean = false
) {

    fun shouldSkipCache(type: CacheType) = skippedCaches.and(type.flag) != 0

    companion object {
        private val allCaches = CacheType.values().fold(0) { prev, next ->
            prev.or(next.flag)
        }

        // TODO figure out if any of these helper methods make sense
        fun <Key> fresh(key: Key) = StoreRequest(
                key = key,
                skippedCaches = allCaches,
                refresh = true
        )

        fun <Key> cached(key: Key, refresh: Boolean) = StoreRequest(
                key = key,
                skippedCaches = 0,
                refresh = refresh
        )

        fun <Key> skipMemory(key: Key, refresh: Boolean) = StoreRequest(
                key = key,
                skippedCaches = CacheType.MEMORY.flag,
                refresh = refresh
        )
    }
}

enum class CacheType(internal val flag: Int) {
    MEMORY(0b01),
    DISK(0b10)
}