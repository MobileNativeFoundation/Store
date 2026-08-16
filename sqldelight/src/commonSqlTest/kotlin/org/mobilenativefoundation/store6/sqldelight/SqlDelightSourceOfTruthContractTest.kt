package org.mobilenativefoundation.store6.sqldelight

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(
    DelicateStoreApi::class,
    ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
internal class SqlDelightSourceOfTruthContractTest : SourceOfTruthContractKit<SqlTestKey, String>() {
    private val harness = freshHarness()

    override fun createSourceOfTruth() = sqlDelightTestSot<SqlTestKey, String>(harness)

    override val keyA = SqlTestKey(ns = "users", id = "a")
    override val keyB = SqlTestKey(ns = "users", id = "b")
    override val keyOtherNamespace = SqlTestKey(ns = "teams", id = "a")

    override fun value(index: Int): String = "value-$index"

    @Test
    fun nestedTransactionKeepsNotificationsKeyScoped(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val matchingValue = value(1)
        val otherValue = value(2)
        sourceOfTruth.write(keyA, matchingValue)
        sourceOfTruth.write(keyOtherNamespace, otherValue)

        turbineScope {
            val matching = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val other = sourceOfTruth.reader(keyOtherNamespace).testIn(backgroundScope)
            try {
                assertEquals(matchingValue, matching.awaitItem())
                assertEquals(otherValue, other.awaitItem())

                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, matchingValue)
                }

                assertEquals(matchingValue, matching.awaitItem())
                runCurrent()
                other.expectNoEvents()
            } finally {
                matching.cancelAndIgnoreRemainingEvents()
                other.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun rollbackPublishesNothingBeforeNextCommittedMutation(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val existing = value(1)
        val committed = value(2)
        sourceOfTruth.write(keyOtherNamespace, existing)

        turbineScope {
            val rolledBack = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val other = sourceOfTruth.reader(keyOtherNamespace).testIn(backgroundScope)
            try {
                assertNull(rolledBack.awaitItem())
                assertEquals(existing, other.awaitItem())

                assertFailsWith<IllegalStateException> {
                    sourceOfTruth.withTransaction {
                        sourceOfTruth.write(keyA, value(3))
                        throw IllegalStateException("roll back")
                    }
                }

                runCurrent()
                rolledBack.expectNoEvents()
                other.expectNoEvents()

                sourceOfTruth.write(keyOtherNamespace, committed)
                assertEquals(committed, other.awaitItem())
                runCurrent()
                rolledBack.expectNoEvents()
            } finally {
                rolledBack.cancelAndIgnoreRemainingEvents()
                other.cancelAndIgnoreRemainingEvents()
            }
        }

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cancellingOneSameKeyReaderKeepsTheOtherReaderLive(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        turbineScope {
            val first = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val second = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            try {
                assertNull(first.awaitItem())
                assertNull(second.awaitItem())
                first.cancelAndIgnoreRemainingEvents()

                sourceOfTruth.write(keyA, current)
                assertEquals(current, second.awaitItem())
            } finally {
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
