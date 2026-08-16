@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit

class SqlDelightBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper =
        freshHarness().let { SqlDelightBookkeeper(it.driver, it.transacter) }
}
