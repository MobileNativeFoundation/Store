package com.dropbox.android.external.store4

import java.util.concurrent.TimeUnit

internal object StoreDefaults {

    /**
     * Default Cache TTL, can be overridden
     *
     * @return memory persister ttl
     */
    val cacheTTL: Long = TimeUnit.HOURS.toSeconds(24)

    /**
     * Default mem persister is 1, can be overridden otherwise
     *
     * @return memory persister size
     */
    val cacheSize: Long = 100

    val cacheTTLTimeUnit: TimeUnit = TimeUnit.SECONDS

    val memoryPolicy = MemoryPolicy.builder()
            .setMemorySize(cacheSize)
            .setExpireAfterWrite(cacheTTL)
            .setExpireAfterTimeUnit(cacheTTLTimeUnit)
            .build()
}
