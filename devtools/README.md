# devtools

UI-free development telemetry for Store6. This published artifact provides a structured logger,
an in-memory monitor, and a composite sink over the Store telemetry seam. Every public entry point
is `@ExperimentalStoreApi`. The seam is a freeze candidate, not frozen.

## Install

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:devtools:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

Opt in to `ExperimentalStoreApi`, import
`org.mobilenativefoundation.store6.devtools.StoreTelemetryLogger`, and add this one builder line:

```kotlin
telemetry(StoreTelemetryLogger())
```

To install a logger and monitor together:

```kotlin
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.devtools.StoreTelemetryLogger
import org.mobilenativefoundation.store6.devtools.storeTelemetryOf

val logger = StoreTelemetryLogger()
val monitor = StoreDevtoolsMonitor()

val users = store<UserKey, User> {
    fetcher(userFetcher)
    telemetry(storeTelemetryOf(logger, monitor))
}
```

The module uses Store6's full 12-target convention: Android, JVM, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `watchosArm64`, `tvosArm64`, JS, WasmJS,
`linuxX64`, and `mingwX64`.

## Monitor projection

`StoreDevtoolsMonitor.state` is a `StateFlow<DevtoolsSnapshot>`. A snapshot contains key summaries
sorted by namespace and canonical key, retained events from oldest to newest, the number of events
dropped from the bounded log, and the latest assigned sequence. `clearLog()` removes retained
events and resets the drop count; it preserves key summaries and the sequence high-water mark.

The key summary is derived only from observed telemetry:

- `fetch_started` → `FETCHING`
- `fetch_succeeded` → `FRESH`, records the event time as the age anchor, and clears the last error
- `fetch_failed` → `ERROR` and records the structured error
- `invalidate` → `STALE`
- `clear` → `CLEARED` and removes the age anchor
- `serve` preserves the current state; when it is the first event for a key, the state is
  `OBSERVED`

This is not cache contents or engine truth. `FRESH` means only that no invalidation, clear, or
failure has been observed since the latest success. It does not mean an engine freshness policy
would accept the value. `MaxAge` expiry emits no event, so it cannot change the projection. Age is
unknown until an observed successful fetch supplies an anchor.

## Installed cost and zero-cost boundary

Each monitor event performs a `StateFlow` compare-and-set update and rebuilds an immutable snapshot
over the current key summaries plus the capacity-bounded event log. Capacity defaults to 500;
overflow drops the oldest retained event and increments `droppedEvents`. Each logger event allocates
one formatted line and invokes its emitter synchronously. Installed telemetry therefore has
nonzero cost. Leaving telemetry unset preserves the core engine's unchanged null fast path.

`benchmarks` `TelemetryOverheadBenchmark` reports these JMH timings in µs/op
(score ± 99.9% score error):

| Path | Unset | Configured no-op |
| --- | ---: | ---: |
| `fetchGet` | 9.85882 ± 0.95009 | 8.76580 ± 0.09741 |
| `residentServe` | 0.206781 ± 0.002764 | 0.199851 ± 0.002588 |
| `streamEmissions` | 84.6412 ± 3.4787 | 82.2187 ± 1.4784 |

No positive configured-no-op timing overhead was resolved. The negative point estimates do not
prove that the no-op sink is faster. The null-guarded unset fast path is structurally established,
but these comparisons do not prove literal zero cost. Separate optional JDK 17 direct-JMH
GC-profiler evidence found resident allocations indistinguishable and fetch/stream allocation
deltas within uncertainty.

## Event vocabulary

[EVENTS.md](EVENTS.md) defines the v0 logger fields, event kinds, escaping, and derived-state rules.
Those fields and their order are fixed for a given published artifact version. Because v0 is
experimental, a later alpha may revise v0; that change is recorded. Cross-alpha parser
compatibility is not guaranteed. v0 is not the Store 6.1 wire format; that decision remains open.
