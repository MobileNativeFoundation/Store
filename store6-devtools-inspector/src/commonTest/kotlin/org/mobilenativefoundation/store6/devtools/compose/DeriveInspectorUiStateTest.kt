/**
 * Honesty pin: policy or `MaxAge` staleness emits no event and is not inferred. `FRESH` means only
 * that no invalidation, clear, or failure has been observed since the latest success.
 */
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools.compose

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DeriveInspectorUiStateTest {
    @Test
    fun futureSuccessAnchorClampsOnlyAgeDeltaToZero() {
        val clock = TestTimeSource()
        val monitor = StoreDevtoolsMonitor(timeSource = clock)
        clock += 5.seconds
        monitor.onFetchSucceeded(TestKey("users", "user-1"), 120.milliseconds)

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = 4.seconds,
            state = InspectorState(),
        )

        assertEquals("age 0.0s", ui.keyRows.single().ageLabel)
        assertEquals("5.0s", ui.eventRows.single().atLabel)
    }

    @Test
    fun saveableItemKeysDistinguishDelimiterCollisions() {
        val first = inspectorItemKey(namespace = "a", key = "b/c")
        val second = inspectorItemKey(namespace = "a/b", key = "c")

        assertEquals("1:a3:b/c", first)
        assertEquals("3:a/b1:c", second)
        assertNotEquals(first, second)
    }

    @Test
    fun keyRowsExposeEventDerivedStateOriginAndAge() {
        val clock = TestTimeSource()
        val monitor = StoreDevtoolsMonitor(timeSource = clock)
        val fresh = TestKey("users", "user-1")
        val unknown = TestKey("users", "user-2")
        monitor.onFetchSucceeded(fresh, 120.milliseconds)
        monitor.onServe(fresh, Origin.SOT)
        monitor.onServe(unknown, Origin.MEMORY)
        clock += 2_300.milliseconds

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = monitor.elapsedNow(),
            state = InspectorState(),
        )

        val freshRow = ui.keyRows.single { it.key == "user-1" }
        assertEquals("FRESH", freshRow.stateLabel)
        assertEquals("SOT", freshRow.originLabel)
        assertEquals("age 2.3s", freshRow.ageLabel)
        val unknownRow = ui.keyRows.single { it.key == "user-2" }
        assertEquals("OBSERVED", unknownRow.stateLabel)
        assertEquals("MEMORY", unknownRow.originLabel)
        assertEquals("age unknown", unknownRow.ageLabel)
    }

    @Test
    fun timelineFiltersTheSelectedKeyOldestToNewestWithExactHeader() {
        val clock = TestTimeSource()
        val monitor = StoreDevtoolsMonitor(timeSource = clock)
        val selectedKey = TestKey("users", "user-1")
        monitor.onFetchStarted(selectedKey)
        clock += 100.milliseconds
        monitor.onServe(TestKey("users", "user-2"), Origin.MEMORY)
        clock += 100.milliseconds
        monitor.onFetchSucceeded(selectedKey, 90.milliseconds)
        clock += 2_300.milliseconds
        val selectedEntry = monitor.state.value.keys.single { it.key == "user-1" }

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = monitor.elapsedNow(),
            state = InspectorState().withKeySelected(selectedEntry),
        )

        assertEquals("users / user-1 — FRESH, age 2.3s", ui.timelineHeader)
        assertEquals(listOf(1L, 3L), ui.timelineRows.map { it.seq })
        assertEquals(listOf("0.0s", "0.2s"), ui.timelineRows.map { it.atLabel })
        assertEquals(
            listOf("fetch_started", "fetch_succeeded"),
            ui.timelineRows.map { it.kindLabel },
        )
    }

    @Test
    fun eventsAreNewestFirstUseV0KindsAndReportDroppedRows() {
        val monitor = StoreDevtoolsMonitor(capacity = 6)
        val key = TestKey("users", "user-1")
        monitor.onServe(key, Origin.MEMORY)
        monitor.onServe(key, Origin.MEMORY)
        monitor.onFetchStarted(key)
        monitor.onFetchSucceeded(key, 120.milliseconds)
        monitor.onFetchFailed(key, TestStoreResults.fetchError("offline"), 340.milliseconds)
        monitor.onServe(key, Origin.FETCHER)
        monitor.onInvalidated(key)
        monitor.onCleared(key)

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = monitor.elapsedNow(),
            state = InspectorState(tab = InspectorTab.Events),
        )

        assertEquals(listOf(8L, 7L, 6L, 5L, 4L, 3L), ui.eventRows.map { it.seq })
        assertEquals(
            listOf(
                "clear",
                "invalidate",
                "serve",
                "fetch_failed",
                "fetch_succeeded",
                "fetch_started",
            ),
            ui.eventRows.map { it.kindLabel },
        )
        assertEquals("origin=FETCHER", ui.eventRows.single { it.kindLabel == "serve" }.detailLabel)
        assertEquals(
            "fetch_ms=340 error=Fetch",
            ui.eventRows.single { it.kindLabel == "fetch_failed" }.detailLabel,
        )
        assertEquals(
            "fetch_ms=120",
            ui.eventRows.single { it.kindLabel == "fetch_succeeded" }.detailLabel,
        )
        assertEquals("2 older events dropped", ui.dropNotice)
    }

    @Test
    fun fetchFailureDetailsUseTheExhaustiveV0ErrorNames() {
        val monitor = StoreDevtoolsMonitor()
        val key = TestKey("users", "user-1")
        val errors = listOf(
            TestStoreResults.fetchError("fetch"),
            TestStoreResults.persistenceError("persistence"),
            TestStoreResults.conversionError("conversion"),
            TestStoreResults.freshnessUnsatisfiable("freshness"),
            TestStoreResults.conflict(serverMeta = null, message = "conflict"),
            TestStoreResults.missing(key, "missing"),
        )
        errors.forEach { monitor.onFetchFailed(key, it, 15.milliseconds) }

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = monitor.elapsedNow(),
            state = InspectorState(tab = InspectorTab.Events),
        )

        assertEquals(
            listOf(
                "fetch_ms=15 error=Missing",
                "fetch_ms=15 error=Conflict",
                "fetch_ms=15 error=FreshnessUnsatisfiable",
                "fetch_ms=15 error=Conversion",
                "fetch_ms=15 error=Persistence",
                "fetch_ms=15 error=Fetch",
            ),
            ui.eventRows.map { it.detailLabel },
        )
    }

    @Test
    fun emptyMonitorShowsTheExactInstallHint() {
        val monitor = StoreDevtoolsMonitor()

        val ui = deriveInspectorUiState(
            snapshot = monitor.state.value,
            now = monitor.elapsedNow(),
            state = InspectorState(),
        )

        assertEquals(
            "No events yet — install with telemetry(monitor) in your store {} builder.",
            ui.emptyHint,
        )
        assertEquals(emptyList(), ui.keyRows)
        assertEquals(emptyList(), ui.eventRows)
        assertNull(ui.dropNotice)
        assertNull(ui.timelineHeader)
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }
}
