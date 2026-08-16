package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class FakeSourceOfTruthContractTest : SourceOfTruthContractKit<TestingKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<TestingKey, String> = FakeSourceOfTruth()
    override val keyA = TestingKey("kit", "a")
    override val keyB = TestingKey("kit", "b")
    override val keyOtherNamespace = TestingKey("kit-other", "a")
    override fun value(index: Int): String = "value-$index"
}
