# store6-devtools-inspector

An in-process Compose Multiplatform inspector for a `StoreDevtoolsMonitor`. The published artifact
is `@ExperimentalStoreApi` and reads the monitor's event-derived `StateFlow`; it does not inspect
Store internals.

## Dependency

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:store6-devtools-inspector:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

## Entry points

```kotlin
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
```

Install the same `monitor` in the Store builder with `telemetry(monitor)`, or combine it with
another sink through `storeTelemetryOf(...)`. `StoreInspector` renders the inspector directly.
`StoreInspectorOverlay` wraps application content with a floating toggle and a lower-half
inspector panel. The two examples are alternative hosts.

The final target subset is Android, JVM, `iosArm64`, `iosSimulatorArm64`, `iosX64`,
`macosArm64`, JS, and WasmJS. JS uses Node. WasmJS uses a browser test/runtime because the Compose
UI dependency graph is browser-only on this toolchain. No other Store6 targets are published by
this artifact.

## What it shows

The current tabs are:

- **Keys:** namespace, canonical key, derived state, last served origin, and observed-success age.
- **Timeline:** chronological per-key rows labelled with the v0 event kind and elapsed offset. The
  header shows the key's current derived state and age.
- **Events:** the retained event log, newest first, with dropped-event accounting.

The timeline is a table of telemetry-derived rows, not a freshness chart. It cannot infer policy
staleness or events that were never observed. The tab model leaves room for a future **Outbox**
view; it is not implemented here. Everything stays in process: there are no sockets, host tools,
or web panel.
