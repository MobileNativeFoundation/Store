package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class FakeBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper = FakeBookkeeper()
}
