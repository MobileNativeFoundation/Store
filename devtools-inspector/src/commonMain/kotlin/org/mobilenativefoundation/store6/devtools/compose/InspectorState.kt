@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.devtools.compose

import org.mobilenativefoundation.store6.devtools.DevtoolsKeyEntry

/**
 * Inspector tabs.
 *
 * The Phase-2 mutation outbox view lands as a fourth entry here, preserving the planned
 * information-architecture slot without affecting the v0 inspector.
 */
internal enum class InspectorTab {
    Keys,
    Timeline,
    Events,
}

internal data class SelectedKey(
    val namespace: String,
    val key: String,
)

internal data class InspectorState(
    val tab: InspectorTab = InspectorTab.Keys,
    val selected: SelectedKey? = null,
) {
    fun withTab(tab: InspectorTab): InspectorState = copy(tab = tab)

    fun withKeySelected(entry: DevtoolsKeyEntry): InspectorState =
        copy(
            tab = InspectorTab.Timeline,
            selected = SelectedKey(entry.namespace, entry.key),
        )
}
