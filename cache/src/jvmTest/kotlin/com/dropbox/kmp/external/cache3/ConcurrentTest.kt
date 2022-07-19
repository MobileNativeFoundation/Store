package com.dropbox.kmp.external.cache3

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentTest {
    @Test
    fun evictEntriesConcurrently() = runTest {
        val cache = cacheBuilder<Long, String> {
            maximumSize(2)
        }

        // should not produce a ConcurrentModificationException
        repeat(10) {
            launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
                cache.put(it.toLong(), "value for $it")
            }
        }
    }

    @Test
    fun expireEntriesConcurrently() = runTest {
        val mutableTicker = MutableTicker()
        val cache = cacheBuilder<Long, String> {
            expireAfterWrite(2.seconds)
            ticker(mutableTicker.ticker)
        }

        repeat(10) {
            cache.put(it.toLong(), "value for $it")
        }

        // should not produce a ConcurrentModificationException
        repeat(10) {
            launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
                cache.getIfPresent(it.toLong())
                mutableTicker += 1.seconds
            }
        }
    }
}
