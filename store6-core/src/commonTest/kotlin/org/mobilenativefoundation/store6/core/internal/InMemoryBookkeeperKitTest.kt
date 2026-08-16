package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
class InMemoryBookkeeperKitTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper = InMemoryBookkeeper()
}
