@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.testing

import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage

class InMemoryJournalStorageContractTest : MutationJournalStorageContractKit() {
    override fun createStorage(): MutationJournalStorage = InMemoryMutationJournalStorage()

    override fun reopenStorage(previous: MutationJournalStorage): MutationJournalStorage = previous
}
