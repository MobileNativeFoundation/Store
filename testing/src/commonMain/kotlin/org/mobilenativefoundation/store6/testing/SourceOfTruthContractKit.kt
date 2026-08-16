@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.testing

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Conformance kit for [SourceOfTruth] implementations. Extend it in your test source set,
 * run your tests: every inherited @Test member executes on every target you compile.
 *
 * ```
 * class MySourceOfTruthContractTest : SourceOfTruthContractKit<MyKey, MyValue>() {
 *     override fun createSourceOfTruth() = MySourceOfTruth()
 *     override val keyA = MyKey("users", "a")
 *     override val keyB = MyKey("users", "b")
 *     override val keyOtherNamespace = MyKey("teams", "a")
 *     override fun value(index: Int) = MyValue("value-$index")
 * }
 * ```
 */
@ExperimentalStoreApi
public abstract class SourceOfTruthContractKit<K : StoreKey, V : Any> {
    /** Creates a fresh source of truth for one contract test. */
    public abstract fun createSourceOfTruth(): SourceOfTruth<K, V>

    /** Returns the first stable test key. */
    public abstract val keyA: K

    /** Returns a stable test key distinct from [keyA] in the same namespace as [keyA]. */
    public abstract val keyB: K

    /** Returns a stable test key in a namespace distinct from [keyA]. */
    public abstract val keyOtherNamespace: K

    /** Returns a stable value for [index], distinct from values returned for other indices. */
    public abstract fun value(index: Int): V

    @Test
    public fun readerStaysLiveAcrossDelete(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val first = value(1)
        val second = value(2)
        sourceOfTruth.write(keyA, first)

        sourceOfTruth.reader(keyA).test {
            assertEquals(first, awaitItem())

            sourceOfTruth.delete(keyA)
            assertNull(awaitItem())

            sourceOfTruth.write(keyA, second)
            assertEquals(second, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerFirstEmissionIsCurrentValue(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerFirstEmissionIsNullWhenAbsent(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun deleteEmitsNull(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            sourceOfTruth.delete(keyA)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerNeverCompletesNormally(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun readerStaysLiveAcrossThreeDeleteCycles(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            repeat(3) { index ->
                val current = value(index)
                sourceOfTruth.write(keyA, current)
                assertEquals(current, awaitItem())
                sourceOfTruth.delete(keyA)
                assertNull(awaitItem())
            }
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun equalValueRewriteEmits(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            sourceOfTruth.write(keyA, current)
            assertEquals(current, awaitItem())
            sourceOfTruth.write(keyA, current)
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun writeIsVisibleToLateReader(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)
        sourceOfTruth.write(keyA, current)

        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun deleteIsVisibleToLateReader(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        sourceOfTruth.write(keyA, value(1))
        sourceOfTruth.delete(keyA)

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun keysAreIsolated(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        sourceOfTruth.reader(keyB).test {
            assertNull(awaitItem())
            sourceOfTruth.write(keyA, current)
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        sourceOfTruth.reader(keyA).test {
            assertEquals(current, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun twoConcurrentReadersBothSeeWrite(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val current = value(1)

        turbineScope {
            val first = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val second = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            try {
                assertNull(first.awaitItem())
                assertNull(second.awaitItem())
                sourceOfTruth.write(keyA, current)
                assertEquals(current, first.awaitItem())
                assertEquals(current, second.awaitItem())
            } finally {
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    public fun deleteNamespaceDeletesOnlyMatchingNamespace(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val matchingA = value(1)
        val matchingB = value(2)
        val other = value(3)
        sourceOfTruth.write(keyA, matchingA)
        sourceOfTruth.write(keyB, matchingB)
        sourceOfTruth.write(keyOtherNamespace, other)

        sourceOfTruth.deleteNamespace(keyA.namespace)

        sourceOfTruth.reader(keyA).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        sourceOfTruth.reader(keyB).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        sourceOfTruth.reader(keyOtherNamespace).test {
            assertEquals(other, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    public fun deleteNamespaceEmitsNullToActiveMatchingReader(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val matchingA = value(1)
        val matchingB = value(2)
        val otherValue = value(3)
        sourceOfTruth.write(keyA, matchingA)
        sourceOfTruth.write(keyB, matchingB)
        sourceOfTruth.write(keyOtherNamespace, otherValue)

        turbineScope {
            val firstMatching = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val secondMatching = sourceOfTruth.reader(keyB).testIn(backgroundScope)
            val other = sourceOfTruth.reader(keyOtherNamespace).testIn(backgroundScope)
            try {
                assertEquals(matchingA, firstMatching.awaitItem())
                assertEquals(matchingB, secondMatching.awaitItem())
                assertEquals(otherValue, other.awaitItem())

                sourceOfTruth.deleteNamespace(StoreNamespace(keyA.namespace.value))

                assertNull(firstMatching.awaitItem())
                assertNull(secondMatching.awaitItem())
                runCurrent()
                other.expectNoEvents()
            } finally {
                firstMatching.cancelAndIgnoreRemainingEvents()
                secondMatching.cancelAndIgnoreRemainingEvents()
                other.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    public fun deleteAllDeletesEveryNamespaceAndEmitsNull(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val first = value(1)
        val second = value(2)
        sourceOfTruth.write(keyA, first)
        sourceOfTruth.write(keyOtherNamespace, second)

        turbineScope {
            val matching = sourceOfTruth.reader(keyA).testIn(backgroundScope)
            val other = sourceOfTruth.reader(keyOtherNamespace).testIn(backgroundScope)
            try {
                assertEquals(first, matching.awaitItem())
                assertEquals(second, other.awaitItem())
                sourceOfTruth.deleteAll()
                assertNull(matching.awaitItem())
                assertNull(other.awaitItem())
            } finally {
                matching.cancelAndIgnoreRemainingEvents()
                other.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    public fun readerStaysLiveAcrossNamespaceDelete(): TestResult = runTest {
        val sourceOfTruth = createSourceOfTruth()
        val first = value(1)
        val second = value(2)
        sourceOfTruth.write(keyA, first)

        sourceOfTruth.reader(keyA).test {
            assertEquals(first, awaitItem())
            sourceOfTruth.deleteNamespace(keyA.namespace)
            assertNull(awaitItem())
            sourceOfTruth.write(keyA, second)
            assertEquals(second, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
