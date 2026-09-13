@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.testing

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Conformance kit for [SourceOfTruth] implementations. Extend it in your test source set,
 * run your tests: every inherited @Test member executes on every target you compile.
 * Checks without `@Test` require an explicit test wrapper in the consumer. Fault checks also
 * require an adapter fixture wired into its actual storage boundary.
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

    /** Checks each mutation's public outcome when its caller is already cancelled. */
    public fun mutations_cancelledCaller_obeyOutcome(): TestResult = runTest {
        SourceMutation.entries.filter { it != SourceMutation.Transaction }.forEach { mutation ->
            assertMutationOutcome(
                SourceOfTruthFaultFixture(createSourceOfTruth(), MutationFaultInjector()),
                mutation,
                null,
                null,
            )
        }
    }

    /** Checks rollback and absent notifications for storage failures and explicit cancellation. */
    public fun mutations_throwBeforeCommit_preserveRowsAndNotifications(
        createFixture: () -> SourceOfTruthFaultFixture<K, V>,
    ): TestResult = runTest {
        SourceMutation.entries.filter { it != SourceMutation.Transaction }.forEach { mutation ->
            listOf(IllegalStateException("storage failed"), CancellationException("storage cancelled"), AssertionError("storage error")).forEach { failure ->
                val fixture = createFixture()
                try {
                    assertMutationOutcome(fixture, mutation, MutationFaultPoint.BeforeCommit, failure)
                } finally {
                    fixture.close()
                }
            }
        }
    }

    /** Cancels the external caller at each storage boundary and checks the public call's outcome. */
    public fun mutations_externalCancellationAtCommit_obeyOutcome(
        createFixture: () -> SourceOfTruthFaultFixture<K, V>,
        points: Set<MutationFaultPoint> = MutationFaultPoint.entries.toSet(),
    ): TestResult = runTest {
        require(points.isNotEmpty())
        SourceMutation.entries.filter { it != SourceMutation.Transaction }.forEach { mutation ->
            points.forEach { point ->
                val fixture = createFixture()
                try {
                    assertMutationOutcome(fixture, mutation, point, null)
                } finally {
                    fixture.close()
                }
            }
        }
    }

    /** Invoke only for a transactional source; a thrown block must roll back every changed key. */
    public fun transactions_throw_preserveRowsAndNotifications(): TestResult = runTest {
        listOf(IllegalStateException("transaction failed"), CancellationException("transaction cancelled"), AssertionError("transaction error")).forEach { failure ->
            val source = createSourceOfTruth()
            val transactional = assertIs<TransactionalSourceOfTruth<K, V>>(source)
            val keys = listOf(keyA, keyB, keyOtherNamespace)
            val before = keys.mapIndexed { index, key -> value(index + 1).also { source.write(key, it) } }
            turbineScope {
                val readers = keys.map { source.reader(it).testIn(backgroundScope) }
                try {
                    readers.forEachIndexed { index, reader -> assertEquals(before[index], reader.awaitItem()) }
                    val observed = observeMutationCall {
                        transactional.withTransaction {
                            source.write(keyA, value(4))
                            source.delete(keyB)
                            source.deleteNamespace(keyOtherNamespace.namespace)
                            throw failure
                        }
                    }
                    assertThrownFailure(failure, observed.completion.exceptionOrNull())
                    keys.forEachIndexed { index, key -> assertEquals(before[index], source.reader(key).first()) }
                    runCurrent()
                    readers.forEach { it.expectNoEvents() }
                    source.write(keyA, value(5))
                    assertEquals(value(5), readers.first().awaitItem())
                    runCurrent()
                    readers.drop(1).forEach { it.expectNoEvents() }
                } finally {
                    readers.forEach { it.cancelAndIgnoreRemainingEvents() }
                }
            }
        }
    }

    /** Checks transaction-wide completion and notifications when the caller is cancelled at commit. */
    public fun transactions_externalCancellationAtCommit_obeyOutcome(
        createFixture: () -> SourceOfTruthFaultFixture<K, V>,
        points: Set<MutationFaultPoint> = MutationFaultPoint.entries.toSet(),
    ): TestResult = runTest {
        require(points.isNotEmpty())
        points.forEach { point ->
            val fixture = createFixture()
            try {
                assertMutationOutcome(fixture, SourceMutation.Transaction, point, null)
            } finally {
                fixture.close()
            }
        }
    }

    private suspend fun TestScope.assertMutationOutcome(
        fixture: SourceOfTruthFaultFixture<K, V>,
        mutation: SourceMutation,
        point: MutationFaultPoint?,
        failure: Throwable?,
    ) {
        val source = fixture.sourceOfTruth
        val keys = listOf(keyA, keyB, keyOtherNamespace)
        val before = keys.mapIndexed { index, key -> value(index + 1).also { source.write(key, it) } }
        turbineScope {
            val readers = keys.map { source.reader(it).testIn(backgroundScope) }
            try {
                readers.forEachIndexed { index, reader -> assertEquals(before[index], reader.awaitItem()) }
                var reached = false
                val observed = observeMutationCall { caller ->
                    if (point == null) {
                        reached = true
                        caller.cancel()
                    } else {
                        fixture.faults.arm(point) {
                            reached = true
                            if (failure != null) throw failure
                            caller.cancel()
                        }
                    }
                    when (mutation) {
                        SourceMutation.Write -> source.write(keyA, value(4))
                        SourceMutation.Delete -> source.delete(keyA)
                        SourceMutation.DeleteNamespace -> source.deleteNamespace(keyA.namespace)
                        SourceMutation.DeleteAll -> source.deleteAll()
                        SourceMutation.Transaction -> assertIs<TransactionalSourceOfTruth<K, V>>(source).withTransaction {
                            source.write(keyA, value(4))
                            source.delete(keyB)
                            source.delete(keyOtherNamespace)
                        }
                    }
                }
                assertTrue(reached, "Storage fault did not fire for $mutation at $point")
                if (failure != null) {
                    assertThrownFailure(failure, observed.completion.exceptionOrNull())
                } else {
                    assertTrue(observed.callerCancelled)
                    observed.completion.exceptionOrNull()?.let { assertIs<CancellationException>(it) }
                    if (point == MutationFaultPoint.AfterCommit) observed.completion.getOrThrow()
                }
                val applied = observed.completion.isSuccess
                val expected: List<V?> = if (!applied) before else when (mutation) {
                    SourceMutation.Write -> listOf(value(4), before[1], before[2])
                    SourceMutation.Delete -> listOf(null, before[1], before[2])
                    SourceMutation.DeleteNamespace -> listOf(null, null, before[2])
                    SourceMutation.DeleteAll -> listOf(null, null, null)
                    SourceMutation.Transaction -> listOf(value(4), null, null)
                }
                keys.forEachIndexed { index, key -> assertEquals(expected[index], source.reader(key).first()) }
                readers.forEachIndexed { index, reader ->
                    if (expected[index] != before[index]) assertEquals(expected[index], reader.awaitItem())
                }
                runCurrent()
                readers.forEach { it.expectNoEvents() }
                source.write(keyA, value(5))
                assertEquals(value(5), readers.first().awaitItem())
                runCurrent()
                readers.drop(1).forEach { it.expectNoEvents() }
            } finally {
                readers.forEach { it.cancelAndIgnoreRemainingEvents() }
            }
        }
    }

    private fun assertThrownFailure(injected: Throwable, thrown: Throwable?) {
        assertNotNull(thrown)
        if (injected is CancellationException) assertIs<CancellationException>(thrown)
        else assertFalse(thrown is CancellationException)
    }

    private enum class SourceMutation { Write, Delete, DeleteNamespace, DeleteAll, Transaction }
}
