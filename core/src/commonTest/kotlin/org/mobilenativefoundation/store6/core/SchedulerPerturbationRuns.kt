@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth

class StoreInvalidationConformanceUnderReaderHopTest : StoreInvalidationConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(
            trackedSot(ReaderHopSourceOfTruth(InMemorySourceOfTruth())),
        )
    }
}

class EmissionSequenceConformanceUnderReaderHopTest : EmissionSequenceConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(
            trackedSot(ReaderHopSourceOfTruth(InMemorySourceOfTruth())),
        )
    }
}

class FreshnessPolicyConformanceUnderReaderHopTest : FreshnessPolicyConformanceTest() {
    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(
            trackedSot(ReaderHopSourceOfTruth(InMemorySourceOfTruth())),
        )
    }
}
