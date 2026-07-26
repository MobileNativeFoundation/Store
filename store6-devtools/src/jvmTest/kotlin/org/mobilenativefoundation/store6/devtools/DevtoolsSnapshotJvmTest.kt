@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DevtoolsSnapshotJvmTest {
    private class TestKey(
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String = id
    }

    @Test
    fun multiElementSnapshotListsRejectJvmMutationAndMonitorRemainsUsable() {
        val monitor = StoreDevtoolsMonitor()
        val first = TestKey("a")
        val second = TestKey("b")
        monitor.onServe(second, Origin.MEMORY)
        monitor.onServe(first, Origin.FETCHER)
        val snapshot = monitor.state.value

        val keyMutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (snapshot.keys as java.util.List<DevtoolsKeyEntry>).add(snapshot.keys.first())
        }.exceptionOrNull()
        val eventMutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (snapshot.events as java.util.List<StoreDevtoolsEvent>).add(snapshot.events.first())
        }.exceptionOrNull()

        monitor.onInvalidated(first)

        assertIs<UnsupportedOperationException>(keyMutation)
        assertIs<UnsupportedOperationException>(eventMutation)
        assertEquals(3, monitor.state.value.lastSeq)
        assertEquals(DevtoolsKeyState.STALE, monitor.state.value.keys.first().state)
        assertIs<StoreDevtoolsEvent.Invalidated>(monitor.state.value.events.last())
    }
}
