@file:OptIn(DelicateStoreApi::class)

package org.mobilenativefoundation.store6.room

import androidx.room3.RoomDatabase
import androidx.room3.deferredTransaction
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.coroutines.cancellation.CancellationException

/**
 * Durable Room [Bookkeeper] backed by the adapter-owned sidecar.
 *
 * Identity is exclusively `(namespace.value, canonicalId())`. One allocator persisted at
 * `store6.sequence` supplies the durable sequence shared by successes, per-key stale marks,
 * namespace watermarks, and the global watermark. A key is durably stale exactly when
 * `max(mark/ns/global) > (success ?: 0)`.
 *
 * Room commits the user value and freshness metadata as two non-atomic durable steps. A crash
 * between them, or a sidecar write failure absorbed by this seam, can leave a persisted value
 * without durable freshness metadata. Rehydration conservatively treats that value as
 * age-unknown/stale.
 *
 * [recordSuccess], [recordFailure], and [forget] absorb storage failures; [status] returns null for
 * unavailable storage or invalid sidecar data. Cooperative caller cancellation always propagates.
 * A Room-internal cancellation from unavailable or closed storage is classified using the active
 * caller context and treated as the corresponding storage failure. Maintenance methods propagate
 * storage failures and remain transaction-atomic.
 */
@ExperimentalStoreApi
public class RoomBookkeeper(
    private val database: RoomDatabase,
    private val dao: Store6BookkeeperDao,
) : Bookkeeper {
    public override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        infallibly {
            inWriteTransaction {
                val sequence = nextSequence()
                val previous = dao.record(namespace, canonicalId)
                dao.upsertRecord(
                    Store6BookkeepingEntity(
                        namespace = namespace,
                        canonicalId = canonicalId,
                        writtenAtEpochMillis = meta.writtenAtEpochMillis,
                        etag = meta.etag,
                        lastSuccessSequence = sequence,
                        lastFailureAtEpochMillis = null,
                        consecutiveFailures = 0,
                        staleSequence = previous?.staleSequence,
                    ),
                )
            }
        }
    }

    public override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        infallibly {
            inWriteTransaction {
                val previous = dao.record(namespace, canonicalId)
                dao.upsertRecord(
                    Store6BookkeepingEntity(
                        namespace = namespace,
                        canonicalId = canonicalId,
                        writtenAtEpochMillis = previous?.writtenAtEpochMillis,
                        etag = previous?.etag,
                        lastSuccessSequence = previous?.lastSuccessSequence,
                        lastFailureAtEpochMillis = atEpochMillis,
                        consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                        staleSequence = previous?.staleSequence,
                    ),
                )
            }
        }
    }

    public override suspend fun status(key: StoreKey): KeyStatus? {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        return try {
            database.useReaderConnection { transactor ->
                transactor.deferredTransaction {
                    val record = dao.record(namespace, canonicalId)
                    val coveringSequence =
                        maxOf(
                            record?.staleSequence ?: 0L,
                            dao.watermark(namespaceScope(namespace)) ?: 0L,
                            dao.watermark(GLOBAL_SCOPE) ?: 0L,
                        )

                    when {
                        record == null && coveringSequence == 0L -> null
                        record == null ->
                            KeyStatus(
                                meta = null,
                                lastSuccessSequence = null,
                                lastFailureAtEpochMillis = null,
                                consecutiveFailures = 0,
                                durablyStale = true,
                            )
                        else -> {
                            val successSequence = record.lastSuccessSequence
                            KeyStatus(
                                meta =
                                    successSequence?.let {
                                        RoomStoreMeta(
                                            writtenAtEpochMillis =
                                                checkNotNull(record.writtenAtEpochMillis) {
                                                    "Room bookkeeping success is missing its " +
                                                        "written timestamp"
                                                },
                                            etag = record.etag,
                                        )
                                    },
                                lastSuccessSequence = successSequence,
                                lastFailureAtEpochMillis =
                                    record.lastFailureAtEpochMillis,
                                consecutiveFailures = record.consecutiveFailures,
                                durablyStale =
                                    coveringSequence > (successSequence ?: 0L),
                            )
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Throwable) {
            null
        }
    }

    public override suspend fun forget(key: StoreKey) {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        infallibly {
            dao.deleteRecord(namespace, canonicalId)
        }
    }

    public override suspend fun markStale(key: StoreKey) {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        inWriteTransaction {
            val sequence = nextSequence()
            val previous = dao.record(namespace, canonicalId)
            dao.upsertRecord(
                Store6BookkeepingEntity(
                    namespace = namespace,
                    canonicalId = canonicalId,
                    writtenAtEpochMillis = previous?.writtenAtEpochMillis,
                    etag = previous?.etag,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = sequence,
                ),
            )
        }
    }

    public override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        inWriteTransaction {
            advanceWatermark(namespaceScope(namespaceValue))
        }
    }

    public override suspend fun advanceGlobalStaleWatermark() {
        inWriteTransaction {
            advanceWatermark(GLOBAL_SCOPE)
        }
    }

    public override suspend fun forgetNamespace(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        inWriteTransaction {
            dao.deleteNamespaceRecords(namespaceValue)
        }
    }

    public override suspend fun forgetAll() {
        inWriteTransaction {
            dao.deleteAllRecords()
        }
    }

    private suspend fun nextSequence(): Long {
        val current = dao.watermark(SEQUENCE_SCOPE) ?: 0L
        check(current < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        val next = current + 1L
        dao.upsertWatermark(Store6WatermarkEntity(SEQUENCE_SCOPE, next))
        return next
    }

    private suspend fun advanceWatermark(scope: String) {
        val sequence = nextSequence()
        dao.upsertWatermark(Store6WatermarkEntity(scope, sequence))
    }

    private suspend fun <T> inWriteTransaction(block: suspend () -> T): T =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                block()
            }
        }

    private suspend inline fun infallibly(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (_: Throwable) {
            // The Bookkeeper contract makes operational writes infallible.
        }
    }

    private companion object {
        private const val SEQUENCE_SCOPE = "store6.sequence"
        private const val GLOBAL_SCOPE = "store6.global"

        fun namespaceScope(namespace: String): String = "ns:$namespace"
    }
}
