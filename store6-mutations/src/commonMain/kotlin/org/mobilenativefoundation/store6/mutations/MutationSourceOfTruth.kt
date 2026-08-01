@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * Mutations-owned in-memory [SourceOfTruth] installed when the builder's persistence door is
 * unset.
 *
 * The factory forwards this exact instance to both the delegated core Store and the mutation
 * engine; core's inaccessible internal default is never substituted, and Issue 024 can select and
 * report its explicit non-transactional fallback because this retained default is visible to it
 * (D9). Certified against `SourceOfTruthContractKit`:
 *
 * - Each row lives in a per-identity versioned state cell keyed on
 *   `(namespace.value, canonicalId())`. Every collection of [reader] immediately first emits the
 *   current row, or `null` when absent, and never completes normally.
 * - Mutations bump the cell version, so an equal-value rewrite still notifies active collections
 *   despite state-flow conflation of equal values.
 * - Cells are retained across [delete], [deleteNamespace], and [deleteAll], so active readers
 *   receive `null` and remain live for later writes; key isolation follows from per-identity
 *   cells.
 * - Mutations mutate one cell reference under the lock with no intervening suspension, so
 *   completion is exception-atomic for every `Throwable`, including cancellation: normal return
 *   means the mutation was applied and its final row notification published, while throwing means
 *   it was not applied.
 *
 * Cells are intentionally unbounded, matching the core default's posture until the issue 007
 * lifecycle policy applies here.
 */
internal class MutationSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private class Row<V : Any>(
        val value: V?,
        val version: Long,
    )

    private val lock = Mutex()
    private val rows = HashMap<KeyIdentity, MutableStateFlow<Row<V>>>()

    override fun reader(key: K): Flow<V?> =
        flow {
            emitAll(rowFor(key).map { row -> row.value })
        }

    override suspend fun write(
        key: K,
        value: V,
    ) {
        publish(key.identity(), value)
    }

    override suspend fun delete(key: K) {
        publish(key.identity(), null)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        lock.withLock {
            rows.forEach { (identity, row) ->
                if (identity.namespace == namespace.value) {
                    row.publishNull()
                }
            }
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            rows.values.forEach { row -> row.publishNull() }
        }
    }

    private suspend fun rowFor(key: K): MutableStateFlow<Row<V>> {
        val identity = key.identity()
        return lock.withLock { rowFor(identity) }
    }

    private suspend fun publish(
        identity: KeyIdentity,
        value: V?,
    ) {
        lock.withLock {
            val row = rowFor(identity)
            val current = row.value
            row.value = Row(value = value, version = current.version + 1L)
        }
    }

    private fun rowFor(identity: KeyIdentity): MutableStateFlow<Row<V>> =
        rows.getOrPut(identity) {
            MutableStateFlow(Row(value = null, version = 0L))
        }

    private fun MutableStateFlow<Row<V>>.publishNull() {
        val current = this.value
        this.value = Row(value = null, version = current.version + 1L)
    }
}
