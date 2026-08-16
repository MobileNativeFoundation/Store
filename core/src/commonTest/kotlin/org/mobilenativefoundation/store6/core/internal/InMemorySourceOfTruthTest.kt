package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.NamespacedTestKey
import org.mobilenativefoundation.store6.core.TestKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalStoreApi::class)
class InMemorySourceOfTruthTest {
    @Test
    fun reader_emitsInitialRowEqualWritesAndDeleteWithoutCompleting() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val key = TestKey("key")

        sourceOfTruth.reader(key).test {
            assertNull(awaitItem())

            sourceOfTruth.write(key, "value")
            assertEquals("value", awaitItem())

            sourceOfTruth.write(TestKey("key"), "value")
            assertEquals("value", awaitItem())

            sourceOfTruth.delete(TestKey("key"))
            assertNull(awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun newReader_startsWithCurrentCanonicalRowAndKeepsNamespacesIsolated() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<NamespacedTestKey, String>()
        sourceOfTruth.write(NamespacedTestKey(ns = "first", id = "key"), "value")

        sourceOfTruth.reader(NamespacedTestKey(ns = "first", id = "key")).test {
            assertEquals("value", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        sourceOfTruth.reader(NamespacedTestKey(ns = "second", id = "key")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
