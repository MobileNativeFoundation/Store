package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class StoreTelemetryTest {
    // events is mutated by 'start'/'success'/'failure' on the fetch coroutine (Dispatchers.Default)
    // strictly BEFORE the FetchTicket outcome completes (the binding placement pin), and by
    // 'serve'/'invalidated'/'cleared' on the caller. Every read below happens after the operation
    // that resumed on that completion returned, so the list is ordered and visible -- no races.
    private class RecordingTelemetry : StoreTelemetry {
        val events = mutableListOf<String>()

        override fun onFetchStarted(key: StoreKey) {
            events += "start"
        }

        override fun onFetchSucceeded(
            key: StoreKey,
            duration: Duration,
        ) {
            events += "success"
        }

        override fun onFetchFailed(
            key: StoreKey,
            error: StoreError,
            duration: Duration,
        ) {
            events += "failure"
        }

        override fun onServe(
            key: StoreKey,
            origin: Origin,
        ) {
            events += "serve:$origin"
        }

        override fun onInvalidated(key: StoreKey) {
            events += "invalidated"
        }

        override fun onCleared(key: StoreKey) {
            events += "cleared"
        }
    }

    @Test
    fun telemetry_observesFetchServeAndMaintenanceAltitude() = runTest {
        val telemetry = RecordingTelemetry()
        val store = store<TestKey, String> {
            fetcher { "v" }
            telemetry(telemetry)
        }
        store.get(TestKey("1"))
        store.invalidate(TestKey("1"))
        store.clear(TestKey("1"))
        store.close()
        // Deterministic BY the placement pin: success happens-before ticket completion, which
        // happens-before get() resuming and calling onServe on this thread.
        assertEquals(
            listOf("start", "success", "serve:FETCHER", "invalidated", "cleared"),
            telemetry.events,
        )
    }

    @Test
    fun telemetry_failureChannel() = runTest {
        val telemetry = RecordingTelemetry()
        val store = store<TestKey, String> {
            fetcher { error("boom") }
            telemetry(telemetry)
        }
        runCatching { store.get(TestKey("1")) } // Failed completes the ticket AFTER onFetchFailed ran.
        store.close()
        assertEquals(listOf("start", "failure"), telemetry.events)
    }

    @Test
    fun bulkClear_notifiesEachFirstSweepResidentExactlyOnce() = runTest {
        val telemetry = RecordingTelemetry()
        val store = store<TestKey, String> {
            fetcher { key -> "v:${key.canonicalId()}" }
            telemetry(telemetry)
        }
        store.get(TestKey("1"))
        store.get(TestKey("2"))
        telemetry.events.clear()

        store.clearNamespace(StoreNamespace("test"))
        store.close()

        assertEquals(listOf("cleared", "cleared"), telemetry.events)
    }

    @Test
    fun unconfiguredTelemetry_behaviorIsUnchanged() = runTest {
        val plain = store<TestKey, String> { fetcher { "v" } }
        val instrumented = store<TestKey, String> {
            fetcher { "v" }
            telemetry(RecordingTelemetry())
        }
        assertNull(plain.runtime()!!.telemetry)
        assertEquals(plain.get(TestKey("1")), instrumented.get(TestKey("1")))
        plain.close()
        instrumented.close()
        // Allocation-count measurement lives in benchmarks.
    }
}
