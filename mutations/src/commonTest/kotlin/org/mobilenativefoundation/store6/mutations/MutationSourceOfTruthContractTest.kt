package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit

/**
 * Certifies the mutations-owned default [MutationSourceOfTruth] against the read-only
 * testing contract kit: the default may not reuse core's inaccessible internal
 * implementation, and may not weaken reader liveness or mutation exception atomicity.
 *
 * Every inherited kit member is a binding test contract and runs on every compiled target:
 * `readerStaysLiveAcrossDelete`, `readerFirstEmissionIsCurrentValue`,
 * `readerFirstEmissionIsNullWhenAbsent`, `deleteEmitsNull`, `readerNeverCompletesNormally`,
 * `readerStaysLiveAcrossThreeDeleteCycles`, `equalValueRewriteEmits`, `writeIsVisibleToLateReader`,
 * `deleteIsVisibleToLateReader`, `keysAreIsolated`, `twoConcurrentReadersBothSeeWrite`,
 * `deleteNamespaceDeletesOnlyMatchingNamespace`, `deleteNamespaceEmitsNullToActiveMatchingReader`,
 * `deleteAllDeletesEveryNamespaceAndEmitsNull`, and `readerStaysLiveAcrossNamespaceDelete`.
 * This subclass adds nothing and overrides nothing beyond the fixtures, so the kit alone owns the
 * assertions. The dedicated two-namespace key type exists because the shared `MutationsTestKey`
 * fixture pins one namespace and the kit requires a cross-namespace key.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class MutationSourceOfTruthContractTest :
    SourceOfTruthContractKit<MutationSourceOfTruthContractTest.ContractKey, String>() {
    class ContractKey(
        ns: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(ns)

        override fun canonicalId(): String = id
    }

    override fun createSourceOfTruth(): SourceOfTruth<ContractKey, String> =
        MutationSourceOfTruth()

    override val keyA = ContractKey("mutations-sot-kit", "a")
    override val keyB = ContractKey("mutations-sot-kit", "b")
    override val keyOtherNamespace = ContractKey("mutations-sot-kit-other", "a")

    override fun value(index: Int): String = "value-$index"
}
