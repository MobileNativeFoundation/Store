package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.NamespacedTestKey
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
class InMemorySourceOfTruthKitTest : SourceOfTruthContractKit<NamespacedTestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<NamespacedTestKey, String> =
        InMemorySourceOfTruth()

    override val keyA: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "a")
    override val keyB: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "b")
    override val keyOtherNamespace: NamespacedTestKey =
        NamespacedTestKey(ns = "other", id = "a")

    override fun value(index: Int): String = "value-$index"
}

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
class SharedFlowSourceOfTruthKitTest : SourceOfTruthContractKit<NamespacedTestKey, String>() {
    override fun createSourceOfTruth(): SourceOfTruth<NamespacedTestKey, String> =
        SharedFlowSourceOfTruth()

    override val keyA: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "a")
    override val keyB: NamespacedTestKey = NamespacedTestKey(ns = "primary", id = "b")
    override val keyOtherNamespace: NamespacedTestKey =
        NamespacedTestKey(ns = "other", id = "a")

    override fun value(index: Int): String = "value-$index"
}

@OptIn(
    DelicateStoreApi::class,
    ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
class SharedFlowSourceOfTruthLinearizationTest {
    @Test
    fun namespaceDeleteSerializesWriteAfterBulkReturn(): TestResult = runTest {
        val keyA = NamespacedTestKey(ns = "primary", id = "a")
        val keyB = NamespacedTestKey(ns = "primary", id = "b")
        val keyCreatedDuringBulk = NamespacedTestKey(ns = "primary", id = "new")
        val bulkEmissionStarted = CompletableDeferred<Unit>()
        val releaseBulkDelete = CompletableDeferred<Unit>()
        val sourceOfTruth =
            SharedFlowSourceOfTruth<NamespacedTestKey, String> {
                bulkEmissionStarted.complete(Unit)
                releaseBulkDelete.await()
            }
        sourceOfTruth.write(keyA, "a")
        sourceOfTruth.write(keyB, "b")

        val bulkDelete = async { sourceOfTruth.deleteNamespace(keyA.namespace) }
        var laterWrite: kotlinx.coroutines.Deferred<Unit>? = null
        var writeInterleaved = false
        try {
            bulkEmissionStarted.await()
            laterWrite = async { sourceOfTruth.write(keyA, "later") }
            runCurrent()
            writeInterleaved = laterWrite.isCompleted
            assertNull(sourceOfTruth.reader(keyCreatedDuringBulk).first())
        } finally {
            releaseBulkDelete.complete(Unit)
        }

        bulkDelete.await()
        checkNotNull(laterWrite).await()

        assertFalse(writeInterleaved)
        assertEquals("later", sourceOfTruth.reader(keyA).first())
    }

    @Test
    fun bulkGateFailureLeavesEveryRowUnchanged(): TestResult = runTest {
        val keyA = NamespacedTestKey(ns = "primary", id = "a")
        val keyB = NamespacedTestKey(ns = "primary", id = "b")
        val failure = IllegalStateException("bulk gate failed")
        val sourceOfTruth =
            SharedFlowSourceOfTruth<NamespacedTestKey, String> {
                throw failure
            }
        sourceOfTruth.write(keyA, "a")
        sourceOfTruth.write(keyB, "b")

        val thrown =
            assertFailsWith<IllegalStateException> {
                sourceOfTruth.deleteAll()
            }

        assertEquals(failure.message, thrown.message)
        assertEquals("a", sourceOfTruth.reader(keyA).first())
        assertEquals("b", sourceOfTruth.reader(keyB).first())
    }
}
