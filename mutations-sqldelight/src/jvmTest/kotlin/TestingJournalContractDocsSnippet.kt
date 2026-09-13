// docs:snippet:mutations-testing-journal-contract
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.SqlDriver
import org.mobilenativefoundation.store6.mutations.sqldelight.SqlDelightMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.testing.MutationJournalStorageContractKit
import kotlin.test.AfterTest

private class JournalFixture(
    val driver: SqlDriver,
    val transacter: Transacter,
) {
    fun storage(): MutationJournalStorage =
        SqlDelightMutationJournalStorage(driver, transacter)
}

class SqlDelightJournalContractTest : MutationJournalStorageContractKit() {
    private val fixtures = mutableListOf<JournalFixture>()
    private val ownerByStorage = mutableMapOf<MutationJournalStorage, JournalFixture>()

    override fun createStorage(): MutationJournalStorage {
        val fixture = freshJournalFixture()
        fixtures += fixture
        return fixture.storage().also { storage -> ownerByStorage[storage] = fixture }
    }

    override fun reopenStorage(previous: MutationJournalStorage): MutationJournalStorage {
        val fixture = checkNotNull(ownerByStorage[previous])
        return fixture.storage().also { storage -> ownerByStorage[storage] = fixture }
    }

    @AfterTest
    fun closeDrivers() {
        var firstFailure: Throwable? = null
        try {
            fixtures.forEach { fixture ->
                try {
                    fixture.driver.close()
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
        } finally {
            fixtures.clear()
            ownerByStorage.clear()
        }
        firstFailure?.let { throw it }
    }
}
// docs:snippet:end

private fun freshJournalFixture(): JournalFixture {
    val harness = org.mobilenativefoundation.store6.mutations.sqldelight.freshJournalHarness()
    return JournalFixture(
        driver = harness.driver,
        transacter = harness.transacter,
    )
}
