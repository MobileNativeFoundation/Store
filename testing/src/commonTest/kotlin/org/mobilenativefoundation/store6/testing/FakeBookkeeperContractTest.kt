package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import kotlin.test.Test

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class FakeBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper = FakeBookkeeper()

    @Test fun globalWatermarkCoverage() = globalWatermark_coversNeverSeenKeys()
    @Test fun laterSuccessCoverage() = laterSuccess_clearsOnlyEarlierStalenessForItsKey()
    @Test fun sharedMonotoneSequence() = marksAndSuccesses_shareOneMonotoneSequence()
    @Test fun forgetCoverage() = forget_removesRecordAndPreservesWatermarks()
    @Test fun forgetNamespaceCoverage() = forgetNamespace_removesOnlyMatchingRecordsAndPreservesWatermarks()
    @Test fun forgetAllCoverage() = forgetAll_removesRecordsAndPreservesWatermarks()
}
