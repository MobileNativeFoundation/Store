# mutations-drain

Maps OS scheduler activations onto `MutationStore.drain()` passes. This artifact owns no
transport and no connectivity monitor.

Every public entry point is `@ExperimentalStoreApi`.

## Install

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:mutations-drain:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

The module uses Store6's full 12-target convention: Android, JVM, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `watchosArm64`, `tvosArm64`, JS, WasmJS,
`linuxX64`, and `mingwX64`.

## Entry points

- `mutationDrainCoordinator(scheduler)` — creates a coordinator and attaches it to
  `scheduler`. An optional `wallClock` timestamps advisory events. The default clock reads
  system time.
- `MutationDrainCoordinator.register(name, store, policy)` — registers one
  `MutationStore` under `name`. Names must match `[A-Za-z0-9._-]{1,64}`.
- `watch(name)` — collects enqueue events for one registration. Collection starts before
  an unconditional launch pass. This function never returns normally. Launch it on a
  `CoroutineScope`. Removing the registration or closing the coordinator completes it with
  `CancellationException`.
- `runActivation(storeName)` — one activation pass. This is the manual trigger, including
  the host hook on an app-active or reachability signal. Do not call it from a
  `MutationServer`, mutator, conflict-policy, or `SourceOfTruth` implementation, or from a
  watch event handler. Those call sites sit on the drain stack and deadlock.
- `reconcile()` — one unconditional pass for each registered store, skipping a store that
  is already mid-pass. Safe to call repeatedly or concurrently with activation passes.
- `InProcessDrainScheduler(scope)` — delay-based scheduler for hosts without an OS
  scheduler and for tests. Constraints are not evaluated. Activations fire after their
  requested delay.

Closing the coordinator completes active `watch` calls with `CancellationException` and
does not close registered stores. Pending scheduler activations are not cancelled.

## Minimal example

```kotlin
    @OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
    val coordinator = mutationDrainCoordinator(InProcessDrainScheduler(scope))
    coordinator.register("com.example.users", users)
    val watch = scope.launch { coordinator.watch("com.example.users") }
    coordinator.runActivation("com.example.users")
```

`users` is a `MutationStore`. `scope` is the `CoroutineScope` that runs in-process
activation delays. Cancel `watch` or close the coordinator to stop collection.

## Registration names

Names persist inside OS payloads across app updates. Pick package-name-like stable names
such as `com.example.users`. Do not derive a name from a build flavor or user state.

Renaming a registration orphans old payloads. An activation for the old name resolves as
unavailable. Launch reconciliation under the new name (`watch` or `reconcile()`) recovers
the work. An OS-backed scheduler may retry that unavailable activation for a bounded
period, then stop.

## Process death and platform gaps

- The default `journalStorage` is in-memory. Scheduling works while the process lives.
  After process death the journal is empty. Durable draining requires durable
  `journalStorage` (`mutations-sqldelight`).
- iOS force-quit suppresses background launches until the user opens the app again. The
  first foreground `watch` or `reconcile()` recovers pending work.
- iOS foreground reconnect without a new write is not covered by OS scheduling. Call
  `runActivation(storeName)` on the app-active signal. That is the host hook. The next
  background grant, enqueue, or launch pass also drains.
- Android Doze and App Standby may defer constraint-met work for hours on idle devices.
  That is latency, not loss, when the journal is durable.
- Multi-process access to one journal is unsupported. One process per journal.
- Activations are at-least-once. Passes are idempotent. Replays and overlapping
  activations are safe.

## OS-backed scheduling

`mutations-drain-meeseeks` maps this artifact onto WorkManager, BGTaskScheduler, Quartz,
and JS through Meeseeks. Use it when activations must outlive the process.
