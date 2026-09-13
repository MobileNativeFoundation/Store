@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import org.mobilenativefoundation.store6.testing.MutationFaultPoint
import kotlin.test.AfterTest
import kotlin.test.Test

class RoomBookkeeperContractTest : BookkeeperContractKit() {
    private val databases = mutableListOf<Store6RoomTestDatabase>()

    override fun createBookkeeper(): Bookkeeper {
        val database = createTestDatabase().also { databases += it }
        return RoomBookkeeper(database, database.store6BookkeeperDao())
    }

    @Test
    fun globalWatermarkCoverage() = globalWatermark_coversNeverSeenKeys()

    @Test
    fun laterSuccessCoverage() = laterSuccess_clearsOnlyEarlierStalenessForItsKey()

    @Test
    fun sharedMonotoneSequence() = marksAndSuccesses_shareOneMonotoneSequence()

    @Test
    fun forgetCoverage() = forget_removesRecordAndPreservesWatermarks()

    @Test
    fun forgetNamespaceCoverage() = forgetNamespace_removesOnlyMatchingRecordsAndPreservesWatermarks()

    @Test
    fun forgetAllCoverage() = forgetAll_removesRecordsAndPreservesWatermarks()

    @Test
    fun maintenanceRollback() = maintenance_throwBeforeCommit_preservesStatus(::roomContractBookkeeperFaultFixture)

    @Test
    fun maintenanceCallerCancellation() = maintenance_externalCancellationAtCommit_obeysOutcome(
        ::roomContractBookkeeperFaultFixture,
        setOf(MutationFaultPoint.BeforeCommit),
    )

    @Test
    fun statusStorageFailureAndRecovery() = status_storageFailure_propagatesAndRecovers(::roomContractStatusFaultFixture)

    @Test
    fun statusCallerCancellationAndRecovery() = status_externalCancellation_propagatesAndRecovers(::roomContractStatusFaultFixture)

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
