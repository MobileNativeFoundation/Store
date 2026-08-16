@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CompositeStoreTelemetryTest {
    @Test
    fun everyHookReachesEverySinkInRegistrationOrder() {
        val calls = mutableListOf<String>()
        val composite = storeTelemetryOf(
            RecordingTelemetry("first", calls),
            RecordingTelemetry("second", calls),
        )

        invokeEveryHook(composite)

        assertEquals(
            listOf(
                "first:fetch_started",
                "second:fetch_started",
                "first:fetch_succeeded",
                "second:fetch_succeeded",
                "first:fetch_failed",
                "second:fetch_failed",
                "first:serve",
                "second:serve",
                "first:invalidate",
                "second:invalidate",
                "first:clear",
                "second:clear",
            ),
            calls,
        )
    }

    @Test
    fun emptyAndSingleSinkFactoriesPreserveTelemetryBehavior() {
        invokeEveryHook(storeTelemetryOf())

        val calls = mutableListOf<String>()
        invokeEveryHook(storeTelemetryOf(RecordingTelemetry("only", calls)))

        assertEquals(
            listOf(
                "only:fetch_started",
                "only:fetch_succeeded",
                "only:fetch_failed",
                "only:serve",
                "only:invalidate",
                "only:clear",
            ),
            calls,
        )
    }

    @Test
    fun publicConstructorSnapshotsTheCallerOwnedSinkList() {
        val calls = mutableListOf<String>()
        val callerOwned = mutableListOf(
            RecordingTelemetry("first", calls),
            RecordingTelemetry("second", calls),
        )
        val composite = CompositeStoreTelemetry(callerOwned)

        callerOwned.clear()
        callerOwned += RecordingTelemetry("replacement", calls)
        composite.onCleared(TestKey("users", "user-1"))

        assertEquals(
            listOf(
                "first:clear",
                "second:clear",
            ),
            calls,
        )
    }

    private fun invokeEveryHook(telemetry: StoreTelemetry) {
        val key = TestKey("users", "user-1")
        telemetry.onFetchStarted(key)
        telemetry.onFetchSucceeded(key, 5.milliseconds)
        telemetry.onFetchFailed(key, TestStoreResults.fetchError("failed"), 6.milliseconds)
        telemetry.onServe(key, Origin.SOT)
        telemetry.onInvalidated(key)
        telemetry.onCleared(key)
    }

    private class RecordingTelemetry(
        private val name: String,
        private val calls: MutableList<String>,
    ) : StoreTelemetry {
        override fun onFetchStarted(key: StoreKey) {
            calls += "$name:fetch_started"
        }

        override fun onFetchSucceeded(key: StoreKey, duration: Duration) {
            calls += "$name:fetch_succeeded"
        }

        override fun onFetchFailed(key: StoreKey, error: StoreError, duration: Duration) {
            calls += "$name:fetch_failed"
        }

        override fun onServe(key: StoreKey, origin: Origin) {
            calls += "$name:serve"
        }

        override fun onInvalidated(key: StoreKey) {
            calls += "$name:invalidate"
        }

        override fun onCleared(key: StoreKey) {
            calls += "$name:clear"
        }
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }
}
