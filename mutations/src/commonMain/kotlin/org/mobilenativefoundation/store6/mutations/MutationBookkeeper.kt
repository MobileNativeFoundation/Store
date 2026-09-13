@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus

/**
 * Mutations-owned in-memory [Bookkeeper] installed when the builder's bookkeeper door is unset.
 *
 * The factory forwards this exact instance to both the delegated core Store and the mutation
 * engine so mutation metadata capture reads the same authority the Store updates; core's
 * inaccessible internal default is never substituted. Certified against
 * `BookkeeperContractKit`:
 *
 * - Identity derives exclusively from `(namespace.value, canonicalId())`; object identity and
 *   concrete key class never participate.
 * - One store-local monotone sequence is shared by successes, per-key stale marks, and
 *   namespace/global watermarks. A key is durably stale exactly when
 *   `max(mark, namespace, global) > (success ?: 0)`; a failure-only record is therefore not
 *   durably stale, and a later success clears earlier staleness.
 * - [recordSuccess] clears the prior failure timestamp and count. [forget], [forgetNamespace],
 *   and [forgetAll] remove key records — including per-key stale marks — but never reset
 *   namespace or global watermarks, and watermarks otherwise only advance.
 * - Maintenance operations stage their full replacement state before publishing it, preserving
 *   the interface's exception atomicity for every `Throwable`, including cancellation: normal
 *   return means the full operation was applied, while throwing means it had no effect.
 *
 * Volatile like every in-memory default: durable staleness is simulated only while clients share
 * this instance. The sequence supports `Long.MAX_VALUE` sequenced operations; a subsequent
 * attempt fails before mutation instead of wrapping.
 */
internal class MutationBookkeeper : Bookkeeper {
    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
    )

    private val lock = Mutex()
    private var records = HashMap<KeyIdentity, Record>()
    private var namespaceStaleWatermarks = HashMap<String, Long>()
    private var globalStaleWatermark = 0L
    private var sequence = 0L
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
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            records[identity] =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
            sequence = nextSequence
        }
    }

    override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            records[identity] =
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
        val identity = key.identity()
        return lock.withLock {
            val record = records[identity]
            val coveringStaleSequence =
                maxOf(
                    record?.staleSequence ?: 0L,
                    namespaceStaleWatermarks[identity.namespace] ?: 0L,
                    globalStaleWatermark,
                )
            when {
                record == null && coveringStaleSequence == 0L -> null
                record == null -> watermarkOnlyStatus
                else ->
                    KeyStatus(
                        meta = record.meta,
                        lastSuccessSequence = record.lastSuccessSequence,
                        lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                        consecutiveFailures = record.consecutiveFailures,
                        durablyStale =
                            coveringStaleSequence > (record.lastSuccessSequence ?: 0L),
                    )
            }
        }
    }

    override suspend fun forget(key: StoreKey) {
        val identity = key.identity()
        lock.withLock {
            records.remove(identity)
        }
    }

    override suspend fun markStale(key: StoreKey) {
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            val staged = copyRecords()
            staged[identity] =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence,
                )
            records = staged
            sequence = nextSequence
        }
    }

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            val staged =
                HashMap<String, Long>(namespaceStaleWatermarks.size + 1).also { copy ->
                    copy.putAll(namespaceStaleWatermarks)
                    copy[namespace.value] = nextSequence
                }
            namespaceStaleWatermarks = staged
            sequence = nextSequence
        }
    }

    override suspend fun advanceGlobalStaleWatermark() {
        lock.withLock {
            val nextSequence = nextSequenceOrThrow()
            globalStaleWatermark = nextSequence
            sequence = nextSequence
        }
    }

    override suspend fun forgetNamespace(namespace: StoreNamespace) {
        lock.withLock {
            val staged = HashMap<KeyIdentity, Record>(records.size)
            records.forEach { (identity, record) ->
                if (identity.namespace != namespace.value) {
                    staged[identity] = record
                }
            }
            records = staged
        }
    }

    override suspend fun forgetAll() {
        lock.withLock {
            records = HashMap()
        }
    }

    private fun copyRecords(): HashMap<KeyIdentity, Record> =
        HashMap<KeyIdentity, Record>(records.size + 1).also { copy -> copy.putAll(records) }

    private fun nextSequenceOrThrow(): Long {
        check(sequence < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        return sequence + 1L
    }
}
