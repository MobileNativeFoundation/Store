package com.dropbox.android.external.store4

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

@ExperimentalTime
internal object StoreDefaults {

    /**
     * Cache TTL (default is 24 hours), can be overridden
     *
     * @return memory cache TTL
     */
    val cacheTTL: Duration = 24.toDuration(DurationUnit.HOURS)

    /**
     * Cache size (default is 100), can be overridden
     *
     * @return memory cache size
     */
    val cacheSize: Long = 100

    val memoryPolicy = MemoryPolicy.builder<Any, Any>()
        .setMaxSize(cacheSize)
        .setExpireAfterWrite(cacheTTL)
        .build()
}
