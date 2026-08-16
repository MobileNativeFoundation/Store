@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.sqldelight

import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.testing.MutationJournalStorageContractKit
import kotlin.test.AfterTest

internal class SqlDelightJournalStorageContractTest : MutationJournalStorageContractKit() {
    private val harnesses = mutableListOf<JournalHarness>()
    private val ownerByStorage = mutableMapOf<MutationJournalStorage, JournalHarness>()

    override fun createStorage(): MutationJournalStorage {
        val harness = freshJournalHarness()
        val storage = harness.storage()
        harnesses += harness
        ownerByStorage[storage] = harness
        return storage
    }

    override fun reopenStorage(previous: MutationJournalStorage): MutationJournalStorage {
        val harness = checkNotNull(ownerByStorage[previous])
        val reopened = harness.storage()
        ownerByStorage[reopened] = harness
        return reopened
    }

    @AfterTest
    fun closeDrivers() {
        var firstFailure: Throwable? = null
        try {
            harnesses.forEach { harness ->
                try {
                    harness.driver.close()
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
        } finally {
            harnesses.clear()
            ownerByStorage.clear()
        }
        firstFailure?.let { throw it }
    }
}
