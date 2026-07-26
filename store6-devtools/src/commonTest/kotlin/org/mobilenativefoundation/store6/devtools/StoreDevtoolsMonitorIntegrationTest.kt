@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest as coroutineRunTest

class StoreDevtoolsMonitorIntegrationTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    @Test
    fun realStoreTelemetryConvergesAcrossFetchInvalidateRefetchAndClear(): TestResult = runTest {
        val monitor = StoreDevtoolsMonitor()
        var fetches = 0
        val store = store<TestKey, String> {
            fetcher { "value-${++fetches}" }
            telemetry(monitor)
        }
        val key = TestKey("users", "42")

        try {
            assertEquals("value-1", store.get(key))
            assertEventTypes(
                monitor,
                "FetchStarted",
                "FetchSucceeded",
                "Served",
            )
            assertEquals(DevtoolsKeyState.FRESH, monitor.state.value.keys.single().state)
            assertEquals(Origin.FETCHER, monitor.state.value.keys.single().lastOrigin)

            store.invalidate(key)
            assertEquals(DevtoolsKeyState.STALE, monitor.state.value.keys.single().state)
            assertIs<StoreDevtoolsEvent.Invalidated>(monitor.state.value.events.last())

            val streamed = store.stream(key, Freshness.MustBeFresh).first {
                it is StoreResult.Data<*>
            }
            val streamedData = assertIs<StoreResult.Data<String>>(streamed)
            assertEquals("value-2", streamedData.value)
            assertEquals(Origin.FETCHER, streamedData.origin)
            assertEventTypes(
                monitor,
                "FetchStarted",
                "FetchSucceeded",
                "Served",
                "Invalidated",
                "FetchStarted",
                "FetchSucceeded",
                "Served",
            )
            assertEquals(DevtoolsKeyState.FRESH, monitor.state.value.keys.single().state)
            assertEquals(2, monitor.state.value.keys.single().fetchCount)
            assertEquals(2, monitor.state.value.keys.single().serveCount)

            store.clear(key)
            assertIs<StoreDevtoolsEvent.Cleared>(monitor.state.value.events.last())
            assertEquals(DevtoolsKeyState.CLEARED, monitor.state.value.keys.single().state)
            assertEquals((1L..8L).toList(), monitor.state.value.events.map { it.seq })
        } finally {
            store.close()
        }
    }

    private fun assertEventTypes(
        monitor: StoreDevtoolsMonitor,
        vararg expected: String,
    ) {
        val actual = monitor.state.value.events.map {
            when (it) {
                is StoreDevtoolsEvent.FetchStarted -> "FetchStarted"
                is StoreDevtoolsEvent.FetchSucceeded -> "FetchSucceeded"
                is StoreDevtoolsEvent.FetchFailed -> "FetchFailed"
                is StoreDevtoolsEvent.Served -> "Served"
                is StoreDevtoolsEvent.Invalidated -> "Invalidated"
                is StoreDevtoolsEvent.Cleared -> "Cleared"
            }
        }
        assertEquals(expected.toList(), actual)
    }
}

// Issue-017 convention: one file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
