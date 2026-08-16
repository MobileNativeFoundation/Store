@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import kotlin.test.AfterTest

internal class RoomSourceOfTruthContractTest :
    SourceOfTruthContractKit<RoomKitKey, String>() {
    private val databases = mutableListOf<Store6RoomTestDatabase>()

    override fun createSourceOfTruth(): SourceOfTruth<RoomKitKey, String> {
        val database = createTestDatabase().also { databases += it }
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
            },
            rowWriter = { key, value ->
                dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
            },
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    override val keyA: RoomKitKey = RoomKitKey(StoreNamespace("users"), "a")
    override val keyB: RoomKitKey = RoomKitKey(StoreNamespace("users"), "b")
    override val keyOtherNamespace: RoomKitKey = RoomKitKey(StoreNamespace("teams"), "a")

    override fun value(index: Int): String = "value-$index"

    @AfterTest
    fun closeDatabases() {
        var firstFailure: Throwable? = null
        databases.forEach { database ->
            try {
                database.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        databases.clear()
        firstFailure?.let { throw it }
    }
}
