package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.SqlDriver
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.sqldelight.internal.DriverAccess
import org.mobilenativefoundation.store6.sqldelight.internal.MetaSidecar
import kotlin.coroutines.cancellation.CancellationException

/**
 * Durable SQLDelight [Bookkeeper] backed by adapter-owned sidecar tables.
 *
 * Canonical identity is exclusively `(namespace.value, canonicalId())`. Every instance backed by
 * the same database shares one monotone sequence across all keys, namespaces, stale marks, and
 * watermarks.
 *
 * This adapter is the leaf lock owner: it acquires no Store locks and serializes each operation in
 * a SQLite transaction. Suspend operations sharing a driver also share one bounded access gate
 * with [SqlDelightSourceOfTruth]. Construct adapters before exposing the driver to concurrent work:
 * sidecar schema setup is synchronous at construction time. Use one logical Store per
 * database/namespace-set. Multiple logical stores sharing a database also share its monotone
 * sequence as one store-local order.
 *
 * [recordSuccess], [recordFailure], and [forget] absorb non-cancellation storage failures.
 * [status] treats a non-cancellation storage failure as unavailable status and returns null, so
 * age is unknown and the caller treats the key as stale. Cancellation always propagates.
 * Maintenance operations rethrow storage failures so their transactions remain exception-atomic.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class SqlDelightBookkeeper(
    driver: SqlDriver,
    private val transacter: Transacter,
) : Bookkeeper {
    private val sidecar = MetaSidecar(driver, transacter)
    private val driverAccess = DriverAccess.forDriver(driver)

    public override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) {
        val ns = key.namespace
        val canonicalId = key.canonicalId()
        absorbing {
            driverAccess.withAccess {
                transacter.transaction { sidecar.recordSuccess(ns.value, canonicalId, meta) }
            }
        }
    }

    public override suspend fun recordFailure(key: StoreKey, atEpochMillis: Long) {
        val ns = key.namespace
        val canonicalId = key.canonicalId()
        absorbing {
            driverAccess.withAccess {
                transacter.transaction {
                    sidecar.recordFailure(ns.value, canonicalId, atEpochMillis)
                }
            }
        }
    }

    public override suspend fun status(key: StoreKey): KeyStatus? {
        val ns = key.namespace
        val canonicalId = key.canonicalId()
        return try {
            driverAccess.withAccess {
                transacter.transactionWithResult { sidecar.readStatus(ns.value, canonicalId) }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            null
        }
    }

    public override suspend fun forget(key: StoreKey) {
        val ns = key.namespace
        val canonicalId = key.canonicalId()
        absorbing {
            driverAccess.withAccess {
                transacter.transaction { sidecar.forget(ns.value, canonicalId) }
            }
        }
    }

    public override suspend fun markStale(key: StoreKey) {
        val ns = key.namespace
        val canonicalId = key.canonicalId()
        driverAccess.withAccess {
            transacter.transaction { sidecar.markStale(ns.value, canonicalId) }
        }
    }

    public override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        driverAccess.withAccess {
            transacter.transaction { sidecar.advanceNamespaceWatermark(namespace.value) }
        }
    }

    public override suspend fun advanceGlobalStaleWatermark() {
        driverAccess.withAccess {
            transacter.transaction { sidecar.advanceGlobalWatermark() }
        }
    }

    public override suspend fun forgetNamespace(namespace: StoreNamespace) {
        driverAccess.withAccess {
            transacter.transaction { sidecar.forgetNamespace(namespace.value) }
        }
    }

    public override suspend fun forgetAll() {
        driverAccess.withAccess {
            transacter.transaction { sidecar.forgetAll() }
        }
    }

    private suspend inline fun absorbing(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
        }
    }
}
