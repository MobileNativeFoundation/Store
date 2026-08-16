@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools.compose

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class InspectorStateTest {
    @Test
    fun tabSelectionPreservesTheSelectedKey() {
        val entry = entry("users", "user-1")
        val selected = InspectorState().withKeySelected(entry)

        val events = selected.withTab(InspectorTab.Events)

        assertEquals(InspectorTab.Events, events.tab)
        assertEquals(SelectedKey("users", "user-1"), events.selected)
    }

    @Test
    fun selectingAKeyJumpsToTimelineAndRecordsItsIdentity() {
        val selected = InspectorState(tab = InspectorTab.Events)
            .withKeySelected(entry("users", "user-1"))

        assertEquals(InspectorTab.Timeline, selected.tab)
        assertEquals(SelectedKey("users", "user-1"), selected.selected)
    }

    @Test
    fun reselectingTheSameKeyIsIdempotent() {
        val entry = entry("users", "user-1")
        val selectedOnce = InspectorState().withKeySelected(entry)

        val selectedTwice = selectedOnce.withKeySelected(entry)

        assertEquals(selectedOnce, selectedTwice)
    }

    @Test
    fun selectionSurvivesSnapshotGrowth() {
        val monitor = StoreDevtoolsMonitor()
        val selectedKey = TestKey("users", "user-1")
        monitor.onServe(selectedKey, Origin.MEMORY)
        val state = InspectorState().withKeySelected(monitor.state.value.keys.single())

        monitor.onServe(TestKey("users", "user-2"), Origin.FETCHER)
        val ui = deriveInspectorUiState(monitor.state.value, Duration.ZERO, state)

        assertEquals(2, ui.keyRows.size)
        assertTrue(ui.keyRows.single { it.key == "user-1" }.isSelected)
        assertFalse(ui.keyRows.single { it.key == "user-2" }.isSelected)
        assertEquals(SelectedKey("users", "user-1"), state.selected)
    }

    private fun entry(
        namespace: String,
        key: String,
    ) = StoreDevtoolsMonitor().run {
        onServe(TestKey(namespace, key), Origin.MEMORY)
        state.value.keys.single()
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }
}
