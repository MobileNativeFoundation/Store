package org.mobilenativefoundation.store6.devtools.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import kotlin.time.Duration.Companion.seconds

/**
 * In-app inspector over a [StoreDevtoolsMonitor]: key browser, per-key timeline, and event log.
 *
 * State shown here is event-derived; policy staleness (`MaxAge` expiry) emits no event and is
 * never inferred. Zero transport: this composable reads the monitor's `StateFlow`, nothing else.
 * The telemetry seam it observes remains a freeze candidate.
 */
@ExperimentalStoreApi
@Composable
public fun StoreInspector(
    monitor: StoreDevtoolsMonitor,
    modifier: Modifier = Modifier,
) {
    val snapshot by monitor.state.collectAsState()
    var state by remember { mutableStateOf(InspectorState()) }
    var now by remember { mutableStateOf(monitor.elapsedNow()) }
    LaunchedEffect(monitor) {
        while (true) {
            now = monitor.elapsedNow()
            delay(1.seconds)
        }
    }
    val ui = deriveInspectorUiState(snapshot, now, state)

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = ui.tab.ordinal) {
            InspectorTab.entries.forEach { tab ->
                Tab(
                    selected = ui.tab == tab,
                    onClick = { state = state.withTab(tab) },
                    text = { Text(tab.name) },
                )
            }
        }
        ui.emptyHint?.let { Text(it, Modifier.padding(16.dp)) }
        when (ui.tab) {
            InspectorTab.Keys -> LazyColumn(Modifier.fillMaxSize()) {
                items(
                    ui.keyRows,
                    key = { inspectorItemKey(namespace = it.namespace, key = it.key) },
                ) { row ->
                    val entry = snapshot.keys.first {
                        it.namespace == row.namespace && it.key == row.key
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state = state.withKeySelected(entry) }
                            .background(
                                if (row.isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("${row.namespace} / ${row.key}", Modifier.weight(1f))
                        Text("${row.stateLabel} · ${row.originLabel} · ${row.ageLabel}")
                    }
                }
            }

            InspectorTab.Timeline -> Column(Modifier.fillMaxSize()) {
                Text(
                    ui.timelineHeader ?: "Select a key in the Keys tab",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                EventList(ui.timelineRows)
            }

            InspectorTab.Events -> Column(Modifier.fillMaxSize()) {
                ui.dropNotice?.let { Text(it, Modifier.padding(horizontal = 12.dp)) }
                EventList(ui.eventRows)
            }
        }
    }
}

internal fun inspectorItemKey(
    namespace: String,
    key: String,
): String = "${namespace.length}:$namespace${key.length}:$key"

@Composable
private fun EventList(rows: List<EventRowUi>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.seq }) { row ->
            if (row.stateLabel == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(row.atLabel, Modifier.padding(end = 8.dp))
                    Text(row.kindLabel, Modifier.weight(1f))
                    Text("${row.keyLabel} ${row.detailLabel}".trim())
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(row.atLabel, Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(row.stateLabel, Modifier.padding(end = 8.dp))
                            Text(row.kindLabel)
                        }
                        Text("${row.keyLabel} ${row.detailLabel}".trim())
                    }
                }
            }
        }
    }
}

/**
 * Wraps app [content] with a floating toggle and [StoreInspector] panel over the lower half.
 *
 * The overlay is the in-app delivery: no host tooling and no transport.
 */
@ExperimentalStoreApi
@Composable
public fun StoreInspectorOverlay(
    monitor: StoreDevtoolsMonitor,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box(modifier.fillMaxSize()) {
        content()
        if (open) {
            Surface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f),
                tonalElevation = 3.dp,
            ) {
                StoreInspector(monitor)
            }
        }
        FloatingActionButton(
            onClick = { open = !open },
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .semantics {
                    contentDescription =
                        if (open) "Close Store6 inspector" else "Open Store6 inspector"
                },
        ) {
            Text(if (open) "×" else "S6")
        }
    }
}
