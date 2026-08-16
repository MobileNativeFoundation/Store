@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.compose

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.FakeStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest as coroutineRunTest

/**
 * Type-only characterization of the closed-store behavior the composables inherit, asserted at the
 * exact `Store.stream` seam they call. The close message is engine-internal diagnostic text, not
 * ABI, so no message text is asserted here.
 */
class ClosedStoreBehaviorTest {
    private class TestKey(val id: String) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String = id
    }

    @Test
    fun streamOnClosedStoreThrowsIllegalStateException() {
        val store = FakeStore<TestKey, String>()
        store.close()
        assertFailsWith<IllegalStateException> { store.stream(TestKey("1")) }
    }

    /**
     * The stream is guarded twice — at call and again at collection start. This is the second
     * guard: the Flow is obtained while the store is open, so only the collection-start check can
     * reject it. That is the path a composable takes when the store closes between composition
     * and the LaunchedEffect body running.
     */
    @Test
    fun streamObtainedBeforeCloseThrowsAtCollectionStart(): TestResult = runTest {
        val store = FakeStore<TestKey, String>()
        val key = TestKey("1")
        store.setValue(key, "v1")
        val stream = store.stream(key)
        store.close()
        assertFailsWith<IllegalStateException> { stream.collect {} }
    }

    @Test
    fun closeDuringCollectionEndsAsCancellation(): TestResult = runTest {
        val store = FakeStore<TestKey, String>()
        val key = TestKey("1")
        store.setValue(key, "v1")
        val collector = launch { store.stream(key).collect {} }
        testScheduler.runCurrent() // collection is live
        store.close()
        collector.join()
        assertTrue(collector.isCancelled)
    }
}

// One file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
