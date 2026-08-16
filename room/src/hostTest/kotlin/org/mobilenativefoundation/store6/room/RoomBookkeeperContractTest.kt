@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.test.AfterTest

class RoomBookkeeperContractTest : BookkeeperContractKit() {
    private val databases = mutableListOf<Store6RoomTestDatabase>()

    override fun createBookkeeper(): Bookkeeper {
        val database = createTestDatabase().also { databases += it }
        return RoomBookkeeper(database, database.store6BookkeeperDao())
    }

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
