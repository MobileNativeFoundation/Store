package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

class TestKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("test")
    override fun canonicalId(): String = id
}

/**
 * Bulk-deletion adapter for fixtures that intentionally model exactly one row in the `test`
 * namespace and ignore key identity for every reader and mutation.
 *
 * These fixtures cannot run the source-of-truth contract kit because they do not provide key
 * isolation. Their namespace/global operations delete that one modeled row through the fixture's
 * existing per-key behavior, preserving any deterministic gate or fault under test.
 */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal interface SingleRowTestSourceOfTruth<V : Any> : SourceOfTruth<TestKey, V> {
    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        if (namespace.value == TEST_NAMESPACE) {
            delete(BULK_DELETE_KEY)
        }
    }

    override suspend fun deleteAll() {
        delete(BULK_DELETE_KEY)
    }

    private companion object {
        const val TEST_NAMESPACE = "test"
        val BULK_DELETE_KEY = TestKey("bulk-delete")
    }
}
