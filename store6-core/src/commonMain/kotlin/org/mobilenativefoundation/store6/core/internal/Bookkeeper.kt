package org.mobilenativefoundation.store6.core.internal

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
 * Engine-owned metadata with deliberate identity equality.
 *
 * A NotModified response creates a new instance so StateFlow re-emits even when the metadata
 * values match the previous successful response.
 */
internal class EngineStoreMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

/**
 * Volatile in-memory [Bookkeeper] with one store-local sequence for successes, marks, and
 * watermarks.
 *
 * This implementation only simulates durable staleness while clients share this instance. Its
 * semantics require retaining the instance, its per-key records, and its namespace/global
 * watermarks for the store lifetime; reconstructing it loses that history. A persistent
 * implementation must durably retain the same information.
 *
 * The sequence supports [Long.MAX_VALUE] successful sequenced operations. A subsequent attempt
 * fails before mutation instead of wrapping; exhaustion is a practical-lifetime invariant
 * failure, not a recoverable storage failure.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class InMemoryBookkeeper(
    private val beforeMaintenancePublishTestGate: () -> Unit = {},
    initialSequence: Long = 0L,
) : Bookkeeper {
    init {
        require(initialSequence >= 0L) { "Bookkeeper sequence must not be negative" }
    }

    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
        var cachedStatus: KeyStatus? = null,
    )

    private val lock = Mutex()
    private var records = HashMap<KeyId, Record>()
    private var namespaceStaleWatermarks = HashMap<String, Long>()
    private var globalStaleWatermark = 0L
    private var sequence = initialSequence
    private val watermarkOnlyStatus =
        KeyStatus(
            meta = null,
            lastSuccessSequence = null,
            lastFailureAtEpochMillis = null,
            consecutiveFailures = 0,
            durablyStale = true,
        )

    override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) {
        val keyId = KeyId.from(key)
        lock.withLock {
            val previous = records[keyId]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
            records[keyId] = nextRecord
            sequence = nextSequence
        }
    }

    override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val keyId = KeyId.from(key)
        lock.withLock {
            val previous = records[keyId]
            records[keyId] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    staleSequence = previous?.staleSequence,
                )
        }
    }

    override suspend fun status(key: StoreKey): KeyStatus? {
        val keyId = KeyId.from(key)
        return lock.withLock {
            val record = records[keyId]
            val coveringStaleSequence =
                maxOf(
                    record?.staleSequence ?: 0L,
                    namespaceStaleWatermarks[keyId.namespace] ?: 0L,
                    globalStaleWatermark,
                )
            if (record == null && coveringStaleSequence == 0L) {
                null
            } else if (record == null) {
                watermarkOnlyStatus
            } else {
                val durablyStale =
                    coveringStaleSequence > (record.lastSuccessSequence ?: 0L)
                record.cachedStatus
                    ?.takeIf { cached -> cached.durablyStale == durablyStale }
                    ?: KeyStatus(
                        meta = record.meta,
                        lastSuccessSequence = record.lastSuccessSequence,
                        lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                        consecutiveFailures = record.consecutiveFailures,
                        durablyStale = durablyStale,
                    ).also { status ->
                        record.cachedStatus = status
                    }
            }
        }
    }

    override suspend fun forget(key: StoreKey) {
        val keyId = KeyId.from(key)
        lock.withLock {
            records.remove(keyId)
        }
    }

    override suspend fun markStale(key: StoreKey) {
        val keyId = KeyId.from(key)
        lock.withLock {
            val previous = records[keyId]
            val nextSequence = nextSequenceOrThrow()
            val stagedRecords = copyRecords()
            stagedRecords[keyId] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence,
                )
            beforeMaintenancePublishTestGate()
            records = stagedRecords
            sequence = nextSequence
        }
    }

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            val stagedWatermarks =
                HashMap<String, Long>(namespaceStaleWatermarks.size).also { staged ->
                    staged.putAll(namespaceStaleWatermarks)
                    staged[namespace.value] = nextSequence
                }
            beforeMaintenancePublishTestGate()
            namespaceStaleWatermarks = stagedWatermarks
            sequence = nextSequence
        }
    }

    override suspend fun advanceGlobalStaleWatermark() {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            beforeMaintenancePublishTestGate()
            globalStaleWatermark = nextSequence
            sequence = nextSequence
        }
    }

    override suspend fun forgetNamespace(namespace: StoreNamespace) {
        lock.withLock {
            val stagedRecords = HashMap<KeyId, Record>(records.size)
            records.forEach { (key, record) ->
                if (key.namespace != namespace.value) {
                    stagedRecords[key] = record
                }
            }
            beforeMaintenancePublishTestGate()
            records = stagedRecords
        }
    }

    override suspend fun forgetAll() {
        lock.withLock {
            val stagedRecords = HashMap<KeyId, Record>()
            beforeMaintenancePublishTestGate()
            records = stagedRecords
        }
    }

    private fun copyRecords(): HashMap<KeyId, Record> =
        HashMap<KeyId, Record>(records.size).also { staged -> staged.putAll(records) }

    private fun nextSequenceOrThrow(): Long {
        check(sequence < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        return sequence + 1L
    }
}
