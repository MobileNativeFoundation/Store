package com.dropbox.android.external.store4

import java.util.concurrent.TimeUnit

internal object StoreDefaults {

    /**
     * Cache TTL (default is 24 hours), can be overridden
     *
     * @return memory cache TTL
     */
    val cacheTTL: Long = TimeUnit.HOURS.toSeconds(24)

    /**
     * Cache size (default is 100), can be overridden
     *
     * @return memory cache size
     */
    val cacheSize: Long = 100

    val cacheTTLTimeUnit: TimeUnit = TimeUnit.SECONDS

    val memoryPolicy = MemoryPolicy.builder()
            .setMemorySize(cacheSize)
            .setExpireAfterWrite(cacheTTL)
            .setExpireAfterTimeUnit(cacheTTLTimeUnit)
            .build()
}
