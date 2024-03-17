package org.mobilenativefoundation.store.store5

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

internal object StoreDefaults {
    /**
     * Cache TTL (default is 24 hours), can be overridden
     *
     * @return memory cache TTL
     */
    val cacheTTL: Duration = 24.hours

    /**
     * Cache size (default is 100), can be overridden
     *
     * @return memory cache size
     */
    val cacheSize: Long = 100

    val memoryPolicy =
        MemoryPolicy.builder<Any, Any>()
            .setMaxSize(cacheSize)
            .setExpireAfterWrite(cacheTTL)
            .build()
}
