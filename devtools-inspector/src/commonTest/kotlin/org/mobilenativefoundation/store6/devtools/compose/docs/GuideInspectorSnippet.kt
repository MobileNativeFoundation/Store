package org.mobilenativefoundation.store6.devtools.compose.docs

// docs:snippet:guides-devtools-inspector-hosts
import androidx.compose.runtime.Composable
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.devtools.compose.StoreInspector
import org.mobilenativefoundation.store6.devtools.compose.StoreInspectorOverlay

@OptIn(ExperimentalStoreApi::class)
@Composable
fun InspectorOnly(monitor: StoreDevtoolsMonitor) {
    StoreInspector(monitor)
}

@OptIn(ExperimentalStoreApi::class)
@Composable
fun AppWithInspector(
    monitor: StoreDevtoolsMonitor,
    content: @Composable () -> Unit,
) {
    StoreInspectorOverlay(monitor = monitor, content = content)
}
// docs:snippet:end
