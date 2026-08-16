package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus

/**
 * In-memory [Bookkeeper] honoring the durable-staleness algebra, certified by
 * [BookkeeperContractKit]: one monotone sequence shared by successes, per-key stale marks, and
 * watermarks; `durablyStale` is true when max(perKeyMark, namespaceWatermark, globalWatermark)
 * exceeds (lastSuccessSequence ?: 0); status() is non-null when a record or a covering watermark
 * exists; watermarks never reset. Records are keyed on `(namespace.value, canonicalId())`.
 * Share one instance across store instances to simulate process restart.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FakeBookkeeper : Bookkeeper {
    private class Record(
        var meta: StoreMeta? = null,
        var lastSuccessSequence: Long? = null,
        var lastFailureAtEpochMillis: Long? = null,
        var consecutiveFailures: Int = 0,
        var staleMark: Long = 0L,
    )

    private val mutex = Mutex()
    private var sequence = 0L
    private val records = HashMap<Pair<String, String>, Record>()
    private val namespaceWatermarks = HashMap<String, Long>()
    private var globalWatermark = 0L

    private fun idOf(key: StoreKey): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta): Unit =
        mutex.withLock {
            val record = records.getOrPut(idOf(key)) { Record() }
            record.meta = meta
            record.lastSuccessSequence = ++sequence
            record.consecutiveFailures = 0
            record.lastFailureAtEpochMillis = null
        }

    override suspend fun recordFailure(key: StoreKey, atEpochMillis: Long): Unit =
        mutex.withLock {
            val record = records.getOrPut(idOf(key)) { Record() }
            record.lastFailureAtEpochMillis = atEpochMillis
            record.consecutiveFailures += 1
        }

    override suspend fun markStale(key: StoreKey): Unit =
        mutex.withLock {
            records.getOrPut(idOf(key)) { Record() }.staleMark = ++sequence
        }

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace): Unit =
        mutex.withLock {
            namespaceWatermarks[namespace.value] = ++sequence
        }

    override suspend fun advanceGlobalStaleWatermark(): Unit =
        mutex.withLock {
            globalWatermark = ++sequence
        }

    override suspend fun status(key: StoreKey): KeyStatus? =
        mutex.withLock {
            val id = idOf(key)
            val record = records[id]
            val covering = maxOf(namespaceWatermarks[id.first] ?: 0L, globalWatermark)
            if (record == null && covering == 0L) return@withLock null
            val mark = maxOf(record?.staleMark ?: 0L, covering)
            KeyStatus(
                meta = record?.meta,
                lastSuccessSequence = record?.lastSuccessSequence,
                lastFailureAtEpochMillis = record?.lastFailureAtEpochMillis,
                consecutiveFailures = record?.consecutiveFailures ?: 0,
                durablyStale = mark > (record?.lastSuccessSequence ?: 0L),
            )
        }

    override suspend fun forget(key: StoreKey): Unit =
        mutex.withLock {
            records.remove(idOf(key))
            Unit
        }

    override suspend fun forgetNamespace(namespace: StoreNamespace): Unit =
        mutex.withLock {
            records.keys.removeAll { it.first == namespace.value }
            Unit
        }

    override suspend fun forgetAll(): Unit = mutex.withLock {
        records.clear()
    }
}
