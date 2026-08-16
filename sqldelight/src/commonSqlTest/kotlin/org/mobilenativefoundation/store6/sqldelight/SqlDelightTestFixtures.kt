package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Query
import kotlinx.coroutines.Dispatchers
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.WallClock
import kotlin.coroutines.CoroutineContext

internal data class SqlTestKey(
    val ns: String,
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(ns)

    override fun canonicalId(): String = id
}

/**
 * Test-only generic bridge for SQLDelight contract fixtures. Test callers bind [V] to [String];
 * the query and value casts below are confined to this common SQL test source set.
 */
@OptIn(ExperimentalStoreApi::class)
internal fun <K : StoreKey, V : Any> sqlDelightTestSot(
    harness: SqlHarness,
    clock: WallClock? = null,
    readContext: CoroutineContext = Dispatchers.Default,
): SqlDelightSourceOfTruth<K, V> =
    SqlDelightSourceOfTruth(
        driver = harness.driver,
        transacter = harness.transacter,
        readQuery = { key ->
            @Suppress("UNCHECKED_CAST")
            (harness.selectRow(key.namespace.value, key.canonicalId()) as Query<V>)
        },
        writeRow = { key, value ->
            harness.upsertRow(key.namespace.value, key.canonicalId(), value as String)
        },
        deleteRow = { key ->
            harness.deleteRow(key.namespace.value, key.canonicalId())
        },
        deleteNamespaceRows = { namespace ->
            harness.deleteNamespace(namespace.value)
        },
        deleteAllRows = harness::deleteAll,
        wallClock = clock,
        readContext = readContext,
    )
