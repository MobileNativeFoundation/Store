package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.test.Test

/**
 * Certifies the mutations-owned default [MutationBookkeeper] against the read-only
 * testing contract kit: the default may not reuse core's inaccessible internal
 * implementation and must satisfy the kit.
 *
 * Every inherited kit member is a binding test contract and runs on every compiled target:
 * `identityDerivation_equalCanonicalPair_sharesRecords`,
 * `identityDerivation_differentCanonicalId_isolated`, `failureOnlyRecord_isNotDurablyStale`,
 * `namespaceWatermark_thenLaterSuccess_clearsDurableStaleness`,
 * `watermarkOnlyKey_reportsDurablyStale`, and `recordSuccess_clearsFailureCountAndTimestamp`.
 * Explicit wrappers invoke the additional watermark, sequence, and forget checks. The kit owns
 * the assertions.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class MutationBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper = MutationBookkeeper()

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
}
