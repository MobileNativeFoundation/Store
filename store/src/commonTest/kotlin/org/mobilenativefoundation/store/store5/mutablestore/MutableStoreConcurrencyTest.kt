@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.Dispatchers
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
 * The queue is a non-thread-safe `ArrayDeque`. Mutating access goes through
 * `withWriteRequestQueueLock`, which historically guarded it with a shared/reader lock that lets
 * multiple holders run concurrently. As a result two operations on the same key could run at once:
 * `addWriteRequestToQueue` doing `add(...)` while `updateWriteRequestQueue` iterates the same deque
 * (`for (writeRequest in this)`). A structural `add` during iteration corrupts the backing array.
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
    private fun newMutableStore(): RealMutableStore<String, Int, Int, Int> {
        val delegate: RealStore<String, Int, Int, Int> =
            testStore(
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
            val mutableStore = newMutableStore()
            val key = "key"
            val responses = (1..500).map { i -> mutableStore.write<Int>(StoreWriteRequest.of(key = key, value = i)) }
            val failures = responses.filterIsInstance<StoreWriteResponse.Error.Exception>()
            assertTrue(
                failures.isEmpty(),
                "Baseline sequential writes should all succeed, but ${failures.size} failed" +
                    (failures.firstOrNull()?.let { ", first error = ${it.error}" } ?: ""),
            )
        }

    @Test
    fun concurrentWritesToSameKey_doNotCorruptWriteQueue() =
        runTest {
            val mutableStore = newMutableStore()
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

                // A corrupted ArrayDeque surfaces as a memory-safety symptom: ConcurrentModificationException,
                // NullPointerException, or IndexOutOfBoundsException on the JVM (EXC_BAD_ACCESS aborts the
                // process on Native, so reaching this assertion at all already proves no native crash).
                // NOTE: concurrent writes to the SAME key can still legitimately fail with
                // IllegalArgumentException("No writes found ...") — a separate, pre-existing logical race
                // where one write drains another's queue entry. That is not memory corruption and is out of
                // scope for this fix, so it is tolerated here.
                val corruption =
                    responses
                        .filterIsInstance<StoreWriteResponse.Error.Exception>()
                        .filter { response ->
                            when (response.error) {
                                is ConcurrentModificationException,
                                is NullPointerException,
                                is IndexOutOfBoundsException,
                                -> true
                                else -> false
                            }
                        }
                assertTrue(
                    corruption.isEmpty(),
                    "Write-queue memory corruption in round $round: ${corruption.size}/${responses.size} " +
                        "writes hit a corruption-class error" +
                        (corruption.firstOrNull()?.let { ", first = ${it.error}" } ?: ""),
                )
            }
        }
}
