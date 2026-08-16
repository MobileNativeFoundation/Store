package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.sqldelight.internal.DriverAccess
import org.mobilenativefoundation.store6.sqldelight.internal.MetaSidecar
import org.mobilenativefoundation.store6.sqldelight.internal.SqlDelightSystemWallClock
import org.mobilenativefoundation.store6.sqldelight.internal.runNonSuspending
import kotlin.coroutines.CoroutineContext

/**
 * SQLDelight-backed source of truth that upholds the Store reader and mutation contract. Every
 * [reader] first emits the current row or `null`, remains live without completing normally, and
 * observes matching writes (including equal-value rewrites) and deletes. A normally returning
 * mutation has applied its final row or absence and published the matching notification; a
 * mutation that throws has not been applied.
 *
 * Every user-row mutation and its matching metadata mutation execute in one [Transacter]
 * transaction, closing the durable user-row/sidecar atomicity boundary. The supplied query and
 * mutation callbacks must use the same [SqlDriver] passed to this adapter so user rows, their
 * transaction, and the sidecar remain coherent.
 *
 * Suspend operations sharing a driver are serialized through one bounded driver-access gate,
 * including [SqlDelightBookkeeper] operations constructed with that driver. Construct adapters
 * before exposing the driver to concurrent work: sidecar schema setup is synchronous at
 * construction time.
 *
 * Live reader signals are instance-scoped: successful commits made through this instance publish
 * to the affected key, namespace, or all active readers, including equal-value rewrites. Raw
 * SQLDelight listener invalidations, direct SQL inside [withTransaction], and changes made through
 * another adapter instance are not bridged into an already-active reader. Those external changes
 * are read from the database by the first emission of every new collection.
 *
 * Write/read round-trip law: after [write] returns normally, a fresh [reader] collection for the
 * same key first emits the written value. Constructor setup, mutation callbacks, and
 * [withTransaction] statements execute on the calling context with no internal dispatching;
 * reader query work uses [readContext], which defaults to [Dispatchers.Default]. A null
 * `wallClock` selects the adapter's own system clock.
 *
 * Present behavior supports synchronous [Transacter] implementations only. A [withTransaction]
 * block must complete without suspension and remain on its calling thread; a genuine suspension
 * fails and rolls back the enclosing transaction. The adapter cancels the block's child job before
 * reporting that failure, preventing cooperatively cancellable work from resuming after rollback.
 * Non-cooperative suspension is unsupported.
 *
 * @param K the key type used to locate a row
 * @param V the non-null row type
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class SqlDelightSourceOfTruth<K : StoreKey, V : Any>(
    driver: SqlDriver,
    private val transacter: Transacter,
    private val readQuery: (K) -> Query<V>,
    private val writeRow: (K, V) -> Unit,
    private val deleteRow: (K) -> Unit,
    private val deleteNamespaceRows: (StoreNamespace) -> Unit,
    private val deleteAllRows: () -> Unit,
    wallClock: WallClock? = null,
    private val readContext: CoroutineContext = Dispatchers.Default,
) : TransactionalSourceOfTruth<K, V> {
    private val sidecar = MetaSidecar(driver, transacter)
    private val driverAccess = DriverAccess.forDriver(driver)
    private val clock = wallClock ?: SqlDelightSystemWallClock
    private val activeReaders = MutableStateFlow<Map<KeyIdentity, ReaderEntry>>(emptyMap())

    override fun reader(key: K): Flow<V?> = flow {
        val identity = KeyIdentity(key)
        val signal = acquireReader(identity)
        try {
            val query = readQuery(key)
            emitAll(
                signal.map {
                    driverAccess.withAccess {
                        withContext(readContext) { query.executeAsOneOrNull() }
                    }
                },
            )
        } finally {
            releaseReader(identity, signal)
        }
    }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        driverAccess.withAccess {
            transacter.transaction {
                publishAfterCommit(MutationScope.Key(KeyIdentity(key)))
                writeRow(key, value)
                sidecar.stampWrite(key.namespace.value, key.canonicalId(), clock.nowEpochMillis())
            }
        }
    }

    override suspend fun delete(key: K) {
        driverAccess.withAccess {
            transacter.transaction {
                publishAfterCommit(MutationScope.Key(KeyIdentity(key)))
                deleteRow(key)
                sidecar.deleteRow(key.namespace.value, key.canonicalId())
            }
        }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        driverAccess.withAccess {
            transacter.transaction {
                publishAfterCommit(MutationScope.Namespace(namespace.value))
                deleteNamespaceRows(namespace)
                sidecar.deleteNamespaceRows(namespace.value)
            }
        }
    }

    override suspend fun deleteAll() {
        driverAccess.withAccess {
            transacter.transaction {
                publishAfterCommit(MutationScope.All)
                deleteAllRows()
                sidecar.deleteAllRows()
            }
        }
    }

    override suspend fun <R> withTransaction(block: suspend () -> R): R =
        driverAccess.withAccess {
            val context = currentCoroutineContext()
            transacter.transactionWithResult { runNonSuspending(context, block) }
        }

    private fun acquireReader(key: KeyIdentity): MutableStateFlow<Long> {
        while (true) {
            val current = activeReaders.value
            val existing = current[key]
            if (existing != null) {
                val updated = current + (key to existing.copy(references = existing.references + 1))
                if (activeReaders.compareAndSet(current, updated)) return existing.signal
            } else {
                val signal = MutableStateFlow(0L)
                val updated = current + (key to ReaderEntry(signal, references = 1))
                if (activeReaders.compareAndSet(current, updated)) return signal
            }
        }
    }

    private fun releaseReader(
        key: KeyIdentity,
        signal: MutableStateFlow<Long>,
    ) {
        while (true) {
            val current = activeReaders.value
            val existing = current[key] ?: return
            if (existing.signal !== signal) return
            val updated =
                if (existing.references == 1L) {
                    current - key
                } else {
                    current + (key to existing.copy(references = existing.references - 1))
                }
            if (activeReaders.compareAndSet(current, updated)) return
        }
    }

    private fun TransactionCallbacks.publishAfterCommit(scope: MutationScope) {
        afterCommit {
            val readers = activeReaders.value
            readers.forEach { (key, entry) ->
                if (scope.affects(key)) entry.signal.update { it + 1L }
            }
        }
    }

    private data class KeyIdentity(
        val namespace: String,
        val canonicalId: String,
    ) {
        constructor(key: StoreKey) : this(key.namespace.value, key.canonicalId())
    }

    private data class ReaderEntry(
        val signal: MutableStateFlow<Long>,
        val references: Long,
    )

    private sealed interface MutationScope {
        fun affects(key: KeyIdentity): Boolean

        data class Key(
            val identity: KeyIdentity,
        ) : MutationScope {
            override fun affects(key: KeyIdentity): Boolean = key == identity
        }

        data class Namespace(
            val value: String,
        ) : MutationScope {
            override fun affects(key: KeyIdentity): Boolean = key.namespace == value
        }

        data object All : MutationScope {
            override fun affects(key: KeyIdentity): Boolean = true
        }
    }
}
