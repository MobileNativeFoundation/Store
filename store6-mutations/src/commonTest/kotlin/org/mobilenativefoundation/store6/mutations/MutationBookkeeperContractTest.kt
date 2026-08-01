package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit

/**
 * R1-11: certifies the mutations-owned default [MutationBookkeeper] against the read-only
 * store6-testing contract kit (D9: the default may not reuse core's inaccessible internal
 * implementation and must satisfy the kit).
 *
 * Every inherited kit member is a binding R1-11 test contract and runs on every compiled target:
 * `identityDerivation_equalCanonicalPair_sharesRecords`,
 * `identityDerivation_differentCanonicalId_isolated`, `failureOnlyRecord_isNotDurablyStale`,
 * `namespaceWatermark_thenLaterSuccess_clearsDurableStaleness`,
 * `watermarkOnlyKey_reportsDurablyStale`, and `recordSuccess_clearsFailureCountAndTimestamp`.
 * This subclass adds nothing and overrides nothing beyond the factory, so the kit alone owns the
 * assertions.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class MutationBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper(): Bookkeeper = MutationBookkeeper()
}
