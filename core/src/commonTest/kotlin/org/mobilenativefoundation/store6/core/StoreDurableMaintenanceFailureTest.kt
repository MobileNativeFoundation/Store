package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class)
class StoreDurableMaintenanceFailureTest {
    @Test
    fun invalidate_whenMarkPaused_doesNotSignalActiveStream_thenFailureIsTyped() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val expectedCause = IllegalStateException("mark unavailable")
        val durableBookkeeper =
            RecordingBookkeeper(markStaleFailure = expectedCause).also {
                it.markEntered = entered
                it.releaseMark = release
            }
        var calls = 0
        val store = store<TestKey, String> {
            fetcher { "v${++calls}" }
            bookkeeper(durableBookkeeper)
        }

        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            val invalidation = backgroundScope.async { runCatching { store.invalidate(TestKey("1")) } }
            entered.await()
            try {
                assertTrue(entered.isCompleted, "invalidate must enter durable mark before signaling")
                expectNoEvents()
                assertEquals(1, calls)
                release.complete(Unit)
                val failure = assertIs<StoreException>(invalidation.await().exceptionOrNull())
                assertPersistenceCause(expectedCause, failure)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            } finally {
                release.complete(Unit)
            }
        }
        store.close()
    }

    @Test
    fun invalidateAll_whenGlobalWatermarkFails_isTypedAndDoesNotSignalActiveStream() = runTest {
        val expectedCause = IllegalStateException("global watermark unavailable")
        val durableBookkeeper = RecordingBookkeeper(advanceWatermarkFailure = expectedCause)
        var calls = 0
        val store = store<TestKey, String> {
            fetcher { "v${++calls}" }
            bookkeeper(durableBookkeeper)
        }

        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            val failure = assertFailsWith<StoreException> { store.invalidateAll() }
            assertPersistenceCause(expectedCause, failure)
            expectNoEvents()
            assertEquals(1, calls)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun invalidateNamespace_whenWatermarkAdvanceFails_isTypedAndDoesNotSignalActiveStream() =
        runTest {
            val expectedCause = IllegalStateException("namespace watermark unavailable")
            val durableBookkeeper = RecordingBookkeeper(advanceWatermarkFailure = expectedCause)
            var calls = 0
            val store = store<TestKey, String> {
                fetcher { "v${++calls}" }
                bookkeeper(durableBookkeeper)
            }

            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                val failure = assertFailsWith<StoreException> {
                    store.invalidateNamespace(StoreNamespace("test"))
                }
                assertPersistenceCause(expectedCause, failure)
                expectNoEvents()
                assertEquals(1, calls)
                cancelAndIgnoreRemainingEvents()
            }
            store.close()
        }

    @Test
    fun clear_whenSotDeleteFails_isTypedLeavesRowAndResidenceAndDoesNotForget() = runTest {
        val events = mutableListOf<String>()
        val expectedCause = IllegalStateException("key delete unavailable")
        val durableBookkeeper = RecordingBookkeeper(events = events)
        val backing = InMemorySourceOfTruth<TestKey, String>()
        val durableSot = RecordingSourceOfTruth(backing, events)
        val key = TestKey("1")
        val store = store<TestKey, String> {
            fetcher { "v1" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        assertEquals("v1", store.get(key))
        durableSot.deleteFailure = expectedCause

        val failure = assertFailsWith<StoreException> { store.clear(key) }
        assertPersistenceCause(expectedCause, failure)
        assertEquals("v1", backing.reader(key).first())
        assertEquals("v1", store.get(key, Freshness.LocalOnly))
        assertTrue(events.none { it == "forget:test/1" })
        store.close()
    }

    @Test
    // Per-key forget remains operationally infallible; this fake is defensive boundary hardening.
    fun clear_whenForgetViolatesContract_isTypedAfterDelete() = runTest {
        val expectedCause = IllegalStateException("forget contract violation")
        val durableBookkeeper = RecordingBookkeeper(forgetFailure = expectedCause)
        val durableSot = InMemorySourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            fetcher { "v1" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        val key = TestKey("1")
        assertEquals("v1", store.get(key))

        val failure = assertFailsWith<StoreException> { store.clear(key) }
        assertPersistenceCause(expectedCause, failure)
        assertNull(durableSot.reader(key).first(), "delete must remain applied before forget fails")
        store.close()
    }

    @Test
    fun clearNamespace_whenForgetFails_isTypedAfterDeleteAndKeepsOtherNamespace() = runTest {
        val events = mutableListOf<String>()
        val expectedCause = IllegalStateException("namespace forget unavailable")
        val durableBookkeeper =
            RecordingBookkeeper(events = events, forgetNamespaceFailure = expectedCause)
        val backing = InMemorySourceOfTruth<NamespacedTestKey, String>()
        val durableSot = RecordingSourceOfTruth(backing, events)
        val a = NamespacedTestKey("a", "1")
        val b = NamespacedTestKey("b", "1")
        val store = store<NamespacedTestKey, String> {
            fetcher { if (it.namespace.value == "a") "va" else "vb" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        store.get(a)
        store.get(b)

        val failure = assertFailsWith<StoreException> {
            store.clearNamespace(StoreNamespace("a"))
        }
        assertPersistenceCause(expectedCause, failure)
        assertNull(backing.reader(a).first())
        assertEquals("vb", backing.reader(b).first())
        assertTrue(
            events.indexOf("deleteNamespace:a") in 0 until events.indexOf("forgetNamespace:a"),
        )
        store.close()
    }

    @Test
    fun clearAll_whenForgetFails_isTypedAfterDeleteAndOrdersSteps() = runTest {
        val events = mutableListOf<String>()
        val expectedCause = IllegalStateException("global forget unavailable")
        val durableBookkeeper = RecordingBookkeeper(events = events, forgetAllFailure = expectedCause)
        val backing = InMemorySourceOfTruth<NamespacedTestKey, String>()
        val durableSot = RecordingSourceOfTruth(backing, events)
        val a = NamespacedTestKey("a", "1")
        val b = NamespacedTestKey("b", "1")
        val store = store<NamespacedTestKey, String> {
            fetcher { "v" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        store.get(a)
        store.get(b)

        val failure = assertFailsWith<StoreException> { store.clearAll() }
        assertPersistenceCause(expectedCause, failure)
        assertNull(backing.reader(a).first())
        assertNull(backing.reader(b).first())
        assertTrue(events.indexOf("deleteAll") in 0 until events.indexOf("forgetAll"))
        store.close()
    }

    @Test
    fun clearNamespace_whenBulkDeleteFails_isTypedDoesNotForgetAndRowsRemain() = runTest {
        val events = mutableListOf<String>()
        val expectedCause = IllegalStateException("namespace delete unavailable")
        val durableBookkeeper = RecordingBookkeeper(events = events)
        val backing = InMemorySourceOfTruth<NamespacedTestKey, String>()
        val durableSot = RecordingSourceOfTruth(backing, events)
        val a = NamespacedTestKey("a", "1")
        val b = NamespacedTestKey("b", "1")
        val store = store<NamespacedTestKey, String> {
            fetcher { if (it.namespace.value == "a") "va" else "vb" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        store.get(a)
        store.get(b)
        durableSot.deleteNamespaceFailure = expectedCause

        val failure = assertFailsWith<StoreException> {
            store.clearNamespace(StoreNamespace("a"))
        }
        assertPersistenceCause(expectedCause, failure)
        assertTrue(events.none { it == "forgetNamespace:a" })
        assertEquals("va", backing.reader(a).first())
        assertEquals("vb", backing.reader(b).first())
        store.close()
    }

    @Test
    fun clearAll_whenBulkDeleteFails_isTypedDoesNotForgetAndRowsRemain() = runTest {
        val events = mutableListOf<String>()
        val expectedCause = IllegalStateException("global delete unavailable")
        val durableBookkeeper = RecordingBookkeeper(events = events)
        val backing = InMemorySourceOfTruth<NamespacedTestKey, String>()
        val durableSot = RecordingSourceOfTruth(backing, events)
        val a = NamespacedTestKey("a", "1")
        val b = NamespacedTestKey("b", "1")
        val store = store<NamespacedTestKey, String> {
            fetcher { "v-${it.namespace.value}" }
            persistence(durableSot)
            bookkeeper(durableBookkeeper)
        }
        store.get(a)
        store.get(b)
        durableSot.deleteAllFailure = expectedCause

        val failure = assertFailsWith<StoreException> { store.clearAll() }
        assertPersistenceCause(expectedCause, failure)
        assertTrue(events.none { it == "forgetAll" })
        assertEquals("v-a", backing.reader(a).first())
        assertEquals("v-b", backing.reader(b).first())
        store.close()
    }

    private fun assertPersistenceCause(
        expectedCause: Throwable,
        failure: StoreException,
    ) {
        val persistence = assertIs<StoreError.Persistence>(failure.error)
        assertSame(expectedCause, persistence.cause)
        assertSame(expectedCause, failure.cause)
    }
}
