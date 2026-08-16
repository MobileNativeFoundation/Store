package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/**
 * In-memory [SourceOfTruth] that passes [SourceOfTruthContractKit] on every target: versioned
 * cells (equal-value rewrites still emit), deletes emit null, readers never complete, and rows are
 * keyed on `(namespace.value, canonicalId())`. Rows are retained for the instance lifetime (test
 * scope).
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FakeSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    private class Row<V : Any>(val version: Long, val value: V?)

    private val rows = MutableStateFlow<Map<Pair<String, String>, Row<V>>>(emptyMap())

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    override fun reader(key: K): Flow<V?> {
        val id = idOf(key)
        return rows
            .map { it[id] }
            .distinctUntilChanged { a, b -> a?.version == b?.version }
            .map { it?.value }
    }

    override suspend fun write(key: K, value: V) {
        val id = idOf(key)
        rows.update { map ->
            map + (id to Row((map[id]?.version ?: 0L) + 1, value))
        }
    }

    override suspend fun delete(key: K) {
        val id = idOf(key)
        rows.update { map ->
            val row = map[id]
            if (row?.value == null) map else map + (id to Row(row.version + 1, null))
        }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        rows.update { map ->
            map.mapValues { (id, row) ->
                if (id.first == namespace.value && row.value != null) {
                    Row(row.version + 1, null)
                } else {
                    row
                }
            }
        }
    }

    override suspend fun deleteAll() {
        rows.update { map ->
            map.mapValues { (_, row) ->
                if (row.value != null) Row(row.version + 1, null) else row
            }
        }
    }
}
