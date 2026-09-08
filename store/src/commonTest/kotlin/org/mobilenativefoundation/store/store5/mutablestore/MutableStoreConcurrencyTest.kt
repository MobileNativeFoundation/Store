@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore
import org.mobilenativefoundation.store.store5.mutablestore.util.TestConverter
import org.mobilenativefoundation.store.store5.mutablestore.util.TestFetcher
import org.mobilenativefoundation.store.store5.mutablestore.util.TestLogger
import org.mobilenativefoundation.store.store5.mutablestore.util.TestValidator
import org.mobilenativefoundation.store.store5.mutablestore.util.testStore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for a data race in [RealMutableStore]'s per-key write-request queue.
 *
 * The queue is a non-thread-safe `ArrayDeque`. Historically, a shared/reader lock allowed
 * admission to mutate it during acknowledgment iteration, corrupting its backing array.
 * A later queue-replacement race could also lose admitted writes. This workload requires every
 * write to complete successfully while exercising admission and acknowledgment across threads.
 *
 * On Kotlin/Native this surfaces as `EXC_BAD_ACCESS` (a hard process crash). On the JVM the deque's
 * fail-fast iterator throws `ConcurrentModificationException`, which `RealMutableStore` catches and
 * converts into a [StoreWriteResponse.Error.Exception]. Either way, with correct mutual exclusion
 * every write should succeed.
 *
 * The delegate is backed by a real thread-safe cache (cache5) with no source of truth, so the only
 * unsynchronized shared mutable state exercised here is the write-request queue itself.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class MutableStoreConcurrencyTest {
    private fun newMutableStore(scope: CoroutineScope): RealMutableStore<String, Int, Int, Int> {
        val delegate: RealStore<String, Int, Int, Int> =
            testStore(
                scope = scope,
                fetcher = TestFetcher(),
                sourceOfTruth = null,
                converter = TestConverter(),
                validator = TestValidator(),
                memoryCache = CacheBuilder<String, Int>().build(),
            )
        return RealMutableStore(
            delegate = delegate,
            updater = Updater.by<String, Int, Int>({ _, value -> UpdaterResult.Success.Typed(value) }),
            bookkeeper = null,
            logger = TestLogger(),
        )
    }

    @Test
    fun sequentialWritesToSameKey_allSucceed() =
        runTest {
            val mutableStore = newMutableStore(backgroundScope)
            val key = "key"
            val responses = (1..500).map { i -> mutableStore.write<Int>(StoreWriteRequest.of(key = key, value = i)) }
            val failures = responses.filterNot { it is StoreWriteResponse.Success }
            assertTrue(
                failures.isEmpty(),
                "Baseline sequential writes should all succeed, but ${failures.size} failed" +
                    (failures.firstOrNull()?.let { ", first error = $it" } ?: ""),
            )
        }

    @Test
    fun concurrentWritesToSameKey_doNotCorruptWriteQueue() =
        runTest {
            val mutableStore = newMutableStore(backgroundScope)
            val key = "key"
            val concurrentWriters = 64
            val rounds = 50

            repeat(rounds) { round ->
                val responses =
                    coroutineScope {
                        (1..concurrentWriters)
                            .map { i ->
                                async(Dispatchers.Default) {
                                    mutableStore.write<Int>(
                                        StoreWriteRequest.of(key = key, value = round * concurrentWriters + i),
                                    )
                                }
                            }
                            .awaitAll()
                    }

                val failures = responses.filterNot { it is StoreWriteResponse.Success }
                assertTrue(
                    failures.isEmpty(),
                    "Write failure in round $round: ${failures.size}/${responses.size} writes failed" +
                        (failures.firstOrNull()?.let { ", first = $it" } ?: ""),
                )
            }
        }
}
