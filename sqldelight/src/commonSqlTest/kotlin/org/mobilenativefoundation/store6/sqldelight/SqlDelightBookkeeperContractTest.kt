@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.test.Test

class SqlDelightBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper =
        freshHarness().let { SqlDelightBookkeeper(it.driver, it.transacter) }

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
    fun maintenanceRollback() = maintenance_throwBeforeCommit_preservesStatus(::sqlContractBookkeeperFaultFixture)

    @Test
    fun maintenanceCallerCancellation() = maintenance_externalCancellationAtCommit_obeysOutcome(
        ::sqlContractBookkeeperFaultFixture,
    )

    @Test
    fun statusStorageFailureAndRecovery() = status_storageFailure_propagatesAndRecovers(::sqlContractStatusFaultFixture)

    @Test
    fun statusCallerCancellationAndRecovery() = status_externalCancellation_propagatesAndRecovers(::sqlContractStatusFaultFixture)
}
