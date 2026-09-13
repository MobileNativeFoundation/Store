@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store6.compose.collectAsState
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreResult

@Composable
fun DemoScreen(store: Store<UserKey, User>, controls: DemoControls) {
    val scope = rememberCoroutineScope()
    val key = remember { UserKey("1") }
    val result by store.collectAsState(key)
    var lastData by remember { mutableStateOf<StoreResult.Data<User>?>(null) }
    val current = result
    val ui = deriveDemoUiState(current, lastData)
    // Retain the last Data for the next composition. Assigning during composition would be a
    // backwards write to state this composition already read; SideEffect defers it until the
    // composition has successfully applied.
    if (current is StoreResult.Data<User>) {
        SideEffect { lastData = current }
    }

    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                val data = ui.card
                when {
                    data != null -> Card(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(data.value.name, style = MaterialTheme.typography.headlineSmall)
                            Text("origin=${data.origin} refreshing=${data.refreshing}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ui.showStaleBadge) {
                                    AssistChip(onClick = {}, label = { Text("STALE") })
                                }
                                ui.errorBanner?.let { banner ->
                                    Text(banner, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    ui.showLoadingPlaceholder -> Text("Loading…")
                    ui.emptyError != null -> Text(
                        "Failed with no local value: ${ui.emptyError}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
                if (ui.showSpinner) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(28.dp))
                }
            }

            val latency by controls.latencyMillis.collectAsState()
            Text("Fetch latency: ${latency}ms")
            Slider(
                value = latency.toFloat(),
                onValueChange = { controls.latencyMillis.value = it.toLong() },
                valueRange = 0f..5000f,
            )
            val failing by controls.failFetches.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fail fetches")
                Switch(checked = failing, onCheckedChange = { controls.failFetches.value = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { store.invalidate(key) } }) { Text("Invalidate") }
                Button(onClick = { scope.launch { store.clear(key) } }) { Text("Clear") }
            }
        }
    }
}
