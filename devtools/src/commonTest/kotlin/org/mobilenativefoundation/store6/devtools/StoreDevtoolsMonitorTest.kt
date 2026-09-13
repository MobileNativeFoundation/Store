@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class StoreDevtoolsMonitorTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    @Test
    fun allHooksRecordTypedOrderedPayloadsAtTheInjectedClock() {
        val clock = TestTimeSource()
        val monitor = StoreDevtoolsMonitor(timeSource = clock)
        val key = TestKey("users", "42")
        val error = TestStoreResults.fetchError("users/42 failed")

        monitor.onFetchStarted(key)
        clock += 1.seconds
        monitor.onFetchSucceeded(key, 7.seconds)
        clock += 1.seconds
        monitor.onFetchFailed(key, error, 8.seconds)
        clock += 1.seconds
        monitor.onServe(key, Origin.SOT)
        clock += 1.seconds
        monitor.onInvalidated(key)
        clock += 1.seconds
        monitor.onCleared(key)

        val events = monitor.state.value.events
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), events.map { it.seq })
        assertEquals(
            listOf(0.seconds, 1.seconds, 2.seconds, 3.seconds, 4.seconds, 5.seconds),
            events.map { it.at },
        )
        events.forEach {
            assertEquals("users", it.namespace)
            assertEquals("42", it.key)
        }
        assertIs<StoreDevtoolsEvent.FetchStarted>(events[0])
        assertEquals(7.seconds, assertIs<StoreDevtoolsEvent.FetchSucceeded>(events[1]).fetchDuration)
        val failed = assertIs<StoreDevtoolsEvent.FetchFailed>(events[2])
        assertSame(error, failed.error)
        assertEquals(8.seconds, failed.fetchDuration)
        assertEquals(Origin.SOT, assertIs<StoreDevtoolsEvent.Served>(events[3]).origin)
        assertIs<StoreDevtoolsEvent.Invalidated>(events[4])
        assertIs<StoreDevtoolsEvent.Cleared>(events[5])
        assertEquals(5.seconds, monitor.elapsedNow())
    }

    @Test
    fun deriveKeyEntryAppliesTheExhaustiveEventTable() {
        val error = TestStoreResults.fetchError("offline")
        val servedOnly = deriveKeyEntry(null, served(seq = 1, at = 1.seconds, origin = Origin.MEMORY))
        assertEntry(
            servedOnly,
            state = DevtoolsKeyState.OBSERVED,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = null,
            lastError = null,
            fetchCount = 0,
            serveCount = 1,
        )

        val fetching = deriveKeyEntry(servedOnly, fetchStarted(seq = 2, at = 2.seconds))
        assertEntry(
            fetching,
            state = DevtoolsKeyState.FETCHING,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = null,
            lastError = null,
            fetchCount = 1,
            serveCount = 1,
        )

        val fresh = deriveKeyEntry(
            fetching,
            StoreDevtoolsEvent.FetchSucceeded(3, 3.seconds, "users", "42", 10.seconds),
        )
        assertEntry(
            fresh,
            state = DevtoolsKeyState.FRESH,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = 3.seconds,
            lastError = null,
            fetchCount = 1,
            serveCount = 1,
        )

        val failed = deriveKeyEntry(
            fresh,
            StoreDevtoolsEvent.FetchFailed(4, 4.seconds, "users", "42", error, 11.seconds),
        )
        assertEntry(
            failed,
            state = DevtoolsKeyState.ERROR,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = 3.seconds,
            lastError = error,
            fetchCount = 1,
            serveCount = 1,
        )

        val recovered = deriveKeyEntry(
            failed,
            StoreDevtoolsEvent.FetchSucceeded(5, 5.seconds, "users", "42", 12.seconds),
        )
        assertEntry(
            recovered,
            state = DevtoolsKeyState.FRESH,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = 5.seconds,
            lastError = null,
            fetchCount = 1,
            serveCount = 1,
        )

        val invalidated = deriveKeyEntry(
            recovered,
            StoreDevtoolsEvent.Invalidated(6, 6.seconds, "users", "42"),
        )
        assertEntry(
            invalidated,
            state = DevtoolsKeyState.STALE,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = 5.seconds,
            lastError = null,
            fetchCount = 1,
            serveCount = 1,
        )

        val cleared = deriveKeyEntry(
            invalidated,
            StoreDevtoolsEvent.Cleared(7, 7.seconds, "users", "42"),
        )
        assertEntry(
            cleared,
            state = DevtoolsKeyState.CLEARED,
            lastOrigin = Origin.MEMORY,
            lastFetchSucceededAt = null,
            lastError = null,
            fetchCount = 1,
            serveCount = 1,
        )

        val servedAfterClear = deriveKeyEntry(
            cleared,
            served(seq = 8, at = 8.seconds, origin = Origin.FETCHER),
        )
        assertEntry(
            servedAfterClear,
            state = DevtoolsKeyState.CLEARED,
            lastOrigin = Origin.FETCHER,
            lastFetchSucceededAt = null,
            lastError = null,
            fetchCount = 1,
            serveCount = 2,
        )
    }

    @Test
    fun monitorStateAppliesTheExhaustiveEventTable() {
        val clock = TestTimeSource()
        val monitor = StoreDevtoolsMonitor(timeSource = clock)
        val key = TestKey("users", "42")
        val error = TestStoreResults.fetchError("offline")

        monitor.onServe(key, Origin.MEMORY)
        assertEquals(DevtoolsKeyState.OBSERVED, monitor.singleKey().state)
        assertNull(monitor.singleKey().lastFetchSucceededAt)
        assertEquals(Origin.MEMORY, monitor.singleKey().lastOrigin)
        assertEquals(1, monitor.singleKey().serveCount)

        monitor.onFetchStarted(key)
        assertEquals(DevtoolsKeyState.FETCHING, monitor.singleKey().state)
        assertEquals(1, monitor.singleKey().fetchCount)

        clock += 2.seconds
        monitor.onFetchSucceeded(key, 1.seconds)
        assertEquals(DevtoolsKeyState.FRESH, monitor.singleKey().state)
        assertEquals(2.seconds, monitor.singleKey().lastFetchSucceededAt)
        assertNull(monitor.singleKey().lastError)

        monitor.onFetchFailed(key, error, 1.seconds)
        assertEquals(DevtoolsKeyState.ERROR, monitor.singleKey().state)
        assertSame(error, monitor.singleKey().lastError)

        clock += 1.seconds
        monitor.onFetchSucceeded(key, 1.seconds)
        assertEquals(DevtoolsKeyState.FRESH, monitor.singleKey().state)
        assertEquals(3.seconds, monitor.singleKey().lastFetchSucceededAt)
        assertNull(monitor.singleKey().lastError)

        monitor.onInvalidated(key)
        assertEquals(DevtoolsKeyState.STALE, monitor.singleKey().state)

        monitor.onCleared(key)
        assertEquals(DevtoolsKeyState.CLEARED, monitor.singleKey().state)
        assertNull(monitor.singleKey().lastFetchSucceededAt)

        monitor.onServe(key, Origin.FETCHER)
        assertEquals(DevtoolsKeyState.CLEARED, monitor.singleKey().state)
        assertEquals(Origin.FETCHER, monitor.singleKey().lastOrigin)
        assertEquals(2, monitor.singleKey().serveCount)
    }

    @Test
    fun keyEntryUpdateReturnsANewImmutableValue() {
        val error = TestStoreResults.fetchError("offline")
        val original = deriveKeyEntry(
            null,
            served(seq = 1, at = 1.seconds, origin = Origin.MEMORY),
        )

        val updated = original.update(
            state = DevtoolsKeyState.ERROR,
            lastError = error,
            fetchCount = 3,
        )

        assertNotSame(original, updated)
        assertEquals(DevtoolsKeyState.OBSERVED, original.state)
        assertNull(original.lastError)
        assertEquals(0, original.fetchCount)
        assertEquals(DevtoolsKeyState.ERROR, updated.state)
        assertSame(error, updated.lastError)
        assertEquals(3, updated.fetchCount)
    }

    @Test
    fun boundedLogDropsOldestEventsAndCountsDrops() {
        val monitor = StoreDevtoolsMonitor(capacity = 3)
        val key = TestKey("users", "42")

        repeat(5) { monitor.onServe(key, Origin.MEMORY) }

        assertEquals(listOf(3L, 4L, 5L), monitor.state.value.events.map { it.seq })
        assertEquals(2, monitor.state.value.droppedEvents)
        assertEquals(5, monitor.state.value.lastSeq)
    }

    @Test
    fun keyEntriesSurviveEventEviction() {
        val monitor = StoreDevtoolsMonitor(capacity = 1)
        val first = TestKey("users", "a")
        val second = TestKey("users", "b")

        monitor.onFetchSucceeded(first, Duration.ZERO)
        monitor.onServe(second, Origin.MEMORY)

        assertEquals(listOf(2L), monitor.state.value.events.map { it.seq })
        assertEquals(listOf("a", "b"), monitor.state.value.keys.map { it.key })
        assertEquals(DevtoolsKeyState.FRESH, monitor.state.value.keys[0].state)
    }

    @Test
    fun keysAggregateByNamespaceAndCanonicalIdInSortedOrder() {
        val monitor = StoreDevtoolsMonitor()

        monitor.onServe(TestKey("zeta", "a"), Origin.MEMORY)
        monitor.onServe(TestKey("alpha", "z"), Origin.SOT)
        monitor.onServe(TestKey("alpha", "a"), Origin.FETCHER)
        monitor.onServe(TestKey("alpha", "a"), Origin.MEMORY)

        assertEquals(
            listOf("alpha/a", "alpha/z", "zeta/a"),
            monitor.state.value.keys.map { "${it.namespace}/${it.key}" },
        )
        assertEquals(2, monitor.state.value.keys.first().serveCount)
    }

    @Test
    fun clearLogKeepsKeysAndSequenceWhileResettingLogAccounting() {
        val monitor = StoreDevtoolsMonitor()
        val key = TestKey("users", "42")
        monitor.onFetchStarted(key)
        val keysBeforeClear = monitor.state.value.keys

        monitor.clearLog()

        assertEquals(emptyList(), monitor.state.value.events)
        assertEquals(0, monitor.state.value.droppedEvents)
        assertEquals(1, monitor.state.value.lastSeq)
        assertEquals(keysBeforeClear, monitor.state.value.keys)

        monitor.onInvalidated(key)
        assertEquals(listOf(2L), monitor.state.value.events.map { it.seq })

        val overflowed = StoreDevtoolsMonitor(capacity = 1)
        overflowed.onServe(key, Origin.MEMORY)
        overflowed.onServe(key, Origin.MEMORY)
        assertEquals(1, overflowed.state.value.droppedEvents)
        overflowed.clearLog()
        assertEquals(0, overflowed.state.value.droppedEvents)
    }

    @Test
    fun snapshotsAndTheirListsRemainValueSnapshots() {
        val monitor = StoreDevtoolsMonitor()
        val key = TestKey("users", "42")
        monitor.onServe(key, Origin.MEMORY)
        val first = monitor.state.value
        val firstKeys = first.keys
        val firstEvents = first.events

        monitor.onFetchStarted(key)
        val second = monitor.state.value

        assertNotSame(first, second)
        assertEquals(DevtoolsKeyState.OBSERVED, first.keys.single().state)
        assertEquals(1, first.keys.single().serveCount)
        assertEquals(1, first.events.size)
        assertEquals(firstKeys, first.keys)
        assertEquals(firstEvents, first.events)

        monitor.clearLog()
        val cleared = monitor.state.value
        assertNotSame(second, cleared)
        assertEquals(2, second.events.size)
        assertEquals(DevtoolsKeyState.FETCHING, second.keys.single().state)
        assertEquals(emptyList(), cleared.events)
    }

    @Test
    fun constructorRejectsNonPositiveCapacity() {
        val zero = assertFailsWith<IllegalArgumentException> { StoreDevtoolsMonitor(capacity = 0) }
        assertEquals("capacity must be greater than zero, was 0.", zero.message)
        assertFailsWith<IllegalArgumentException> { StoreDevtoolsMonitor(capacity = -1) }
    }

    private fun StoreDevtoolsMonitor.singleKey(): DevtoolsKeyEntry = state.value.keys.single()

    private fun fetchStarted(
        seq: Long,
        at: Duration,
    ): StoreDevtoolsEvent.FetchStarted =
        StoreDevtoolsEvent.FetchStarted(seq, at, "users", "42")

    private fun served(
        seq: Long,
        at: Duration,
        origin: Origin,
    ): StoreDevtoolsEvent.Served =
        StoreDevtoolsEvent.Served(seq, at, "users", "42", origin)

    private fun assertEntry(
        entry: DevtoolsKeyEntry,
        state: DevtoolsKeyState,
        lastOrigin: Origin?,
        lastFetchSucceededAt: Duration?,
        lastError: Any?,
        fetchCount: Long,
        serveCount: Long,
    ) {
        assertEquals("users", entry.namespace)
        assertEquals("42", entry.key)
        assertEquals(state, entry.state)
        assertEquals(lastOrigin, entry.lastOrigin)
        assertEquals(lastFetchSucceededAt, entry.lastFetchSucceededAt)
        assertSame(lastError, entry.lastError)
        assertEquals(fetchCount, entry.fetchCount)
        assertEquals(serveCount, entry.serveCount)
    }
}
