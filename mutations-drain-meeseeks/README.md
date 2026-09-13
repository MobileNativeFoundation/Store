# mutations-drain-meeseeks

Maps [`mutations-drain`](../mutations-drain/README.md) activations onto WorkManager,
BGTaskScheduler, Quartz, and the Meeseeks JS runner through
[Meeseeks](https://github.com/matt-ramotar/meeseeks) 1.1.1
(`dev.mattramotar.meeseeks:runtime:1.1.1`).

This artifact never calls `Meeseeks.initialize`. The host owns the manager, the
`register<StoreDrainPayload>` worker line, the WorkManager factory, and the iOS
Info.plist identifiers.

Every public entry point is `@ExperimentalStoreApi`.

## Install

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:mutations-drain-meeseeks:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

This artifact publishes Android, JVM, `iosArm64`, `iosSimulatorArm64`, `iosX64`, and JS.
Depend on it from a source set that participates in that matrix. A 12-target `commonMain`
cannot resolve the missing targets.

Meeseeks 1.1.1 publishes Java 17 bytecode and a public inline API. JVM compilation and
tests that consume this artifact require a Java 17 toolchain. This module sets
`jvmToolchain(17)`.

## Host wiring

Create one `MeeseeksDrainScheduler` whose `manager` lambda returns the same `BGTaskManager`
for the scheduler's lifetime. `mutationDrainCoordinator(drainScheduler)` attaches the
coordinator. Call `register` then launch `watch`. `watch` subscribes before it runs the
unconditional launch pass.

An app that already initializes Meeseeks adds one `register<StoreDrainPayload>` line to
that existing block and shares the manager.

The JVM block is copied from the compiled snippet
`src/jvmTest/kotlin/org/mobilenativefoundation/store6/mutations/drain/meeseeks/docs/MeeseeksWiringDocsSnippet.kt`
(`wireJvmHost`). That function is not a test and must not be invoked. See
[JVM initialization](#jvm-initialization).

### JVM

Default `DrainConstraints` set `requiresNetwork = true`. Meeseeks rejects that on the JVM,
so the snippet registers both flags as `false`.

```kotlin
    @OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
    lateinit var bgTaskManager: BGTaskManager
    val drainScheduler = MeeseeksDrainScheduler(manager = { bgTaskManager })
    bgTaskManager =
        Meeseeks.initialize(appContext) {
            register<StoreDrainPayload> { workerContext ->
                StoreDrainWorker(workerContext, drainScheduler)
            }
        }
    val coordinator = mutationDrainCoordinator(drainScheduler)
    coordinator.register(
        "com.example.users",
        users,
        DrainPolicy(
            constraints = DrainConstraints(
                requiresNetwork = false,
                requiresCharging = false,
            ),
        ),
    )
    val watch = scope.launch { coordinator.watch("com.example.users") }
    scope.launch { coordinator.runActivation("com.example.users") }
```

Desktop hosts that do not already run Meeseeks should use `InProcessDrainScheduler` from
`mutations-drain` instead.

### Android

Adapted from the compiled JVM snippet and Meeseeks'
[Android guide](https://github.com/matt-ramotar/meeseeks/blob/main/docs/platforms/android.md).
On Android, Meeseeks' `AppContext` is `Context`. Network and charging constraints are
supported, so the default `DrainPolicy` is valid.

```kotlin
class App : Application(), Configuration.Provider {
    val drainScheduler = MeeseeksDrainScheduler(manager = { bgTaskManager })

    val bgTaskManager: BGTaskManager by lazy {
        Meeseeks.initialize(this) {
            register<StoreDrainPayload> { appContext ->
                StoreDrainWorker(appContext, drainScheduler)
            }
        }
    }

    val coordinator = mutationDrainCoordinator(drainScheduler)

    override fun onCreate() {
        super.onCreate()
        coordinator.register("com.example.users", users)
        appScope.launch { coordinator.watch("com.example.users") }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                DelegatingWorkerFactory().apply {
                    addFactory(MeeseeksWorkerFactory(bgTaskManager))
                },
            )
            .build()
}
```

### iOS

Adapted from the compiled JVM snippet and Meeseeks'
[iOS guide](https://github.com/matt-ramotar/meeseeks/blob/main/docs/platforms/ios.md).
Supply the host `AppContext` as that guide specifies. Network and charging constraints
are supported.

```kotlin
lateinit var bgTaskManager: BGTaskManager
val drainScheduler = MeeseeksDrainScheduler(manager = { bgTaskManager })
bgTaskManager =
    Meeseeks.initialize(appContext) {
        register<StoreDrainPayload> { workerContext ->
            StoreDrainWorker(workerContext, drainScheduler)
        }
    }
val coordinator = mutationDrainCoordinator(drainScheduler)
coordinator.register("com.example.users", users)
scope.launch { coordinator.watch("com.example.users") }
```

List Meeseeks' two identifiers under `BGTaskSchedulerPermittedIdentifiers`. This artifact
adds none of its own.

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.mattramotar.meeseeks.task.refresh</string>
    <string>dev.mattramotar.meeseeks.task.processing</string>
</array>
```

BGTask activations do not run while the app is foregrounded. For a foreground reconnect
without a new write, call `runActivation` on the app-active or reachability signal:

```kotlin
scope.launch { coordinator.runActivation("com.example.users") }
```

## Platform behavior

| Platform | Mechanism | Constraint support | Behavior notes |
|---|---|---|---|
| Android | WorkManager (`BGTaskCoroutineWorker` + `MeeseeksWorkerFactory`) | network, charging | Constraint-met work runs in foreground or background, subject to Doze, App Standby buckets, and background restrictions. Deferral by hours is possible on idle devices. The adapter does not request expedited dispatch. Requests survive process death and reboot in WorkManager's store. |
| iOS | BGTaskScheduler (`BGAppRefreshTask` / `BGProcessingTask` under Meeseeks' identifiers) | network, charging | Wakes are OS-managed and best-effort. Activations do not fire while the app is foregrounded. Force-quit suppresses launches until the next user open. Meeseeks states that its database is the source of truth and that platform task requests are hints to the OS. The mutation journal is the drain source of truth. |
| JVM | Quartz | none (Meeseeks fails fast on constraints) | Registration with default constraints fails at `validate`. The message names the fix: `DrainConstraints(requiresNetwork = false, requiresCharging = false)` or `InProcessDrainScheduler`. Meeseeks 1.1.1 initializes but does not execute scheduled tasks with its bundled Quartz store. See [JVM scheduling](#jvm-scheduling). Desktop hosts should use `InProcessDrainScheduler` from `mutations-drain` until upstream fixes ship. |
| JS | Meeseeks runner | none | Same `validate` behavior as JVM. Browser and Node hosts should prefer `InProcessDrainScheduler`. There is no background execution beyond the live page or process. |

## Constraint validation

Meeseeks rejects unsupported preconditions at schedule time with `IllegalArgumentException`
and no silent downgrade. The adapter surfaces that failure at `register` through
`DrainScheduler.validate`. The exception names the platform and the unsupported keys
(`requiresNetwork`, `requiresCharging`). The fix strings are
`DrainConstraints(requiresNetwork = false, requiresCharging = false)` or
`InProcessDrainScheduler`.

## JVM scheduling

Meeseeks 1.1.1 fixes the 1.1.0 initialization failure: `Meeseeks.initialize` completes on
a stock classpath. Scheduled tasks still do not execute. The bundled Quartz configuration
pairs the JDBC job store's stock delegate (`StdJDBCDelegate`) with the SQLite driver,
which does not implement JDBC features that delegate requires; trigger operations fail
with `SQLFeatureNotSupportedException` (surfaced as `JobPersistenceException`), and
scheduled work never fires. A host-supplied root-classpath `quartz.properties` cannot
switch to a non-JDBC store, because Meeseeks' factory requires
`org.quartz.jobStore.dataSource`. Separately, `listTasks` throws
`IllegalArgumentException` ("Unknown payload type id") when the Meeseeks database holds a
payload type the current process has not registered, so the adapter's recovery scan
cannot survive foreign rows.

This artifact's JVM execution and recovery suites (`MeeseeksExecutionIntegrationTest`,
`MeeseeksRecoveryIntegrationTest`) are red on those behaviors and gate release of this
artifact until upstream fixes ship. Default `jvmTest` excludes those two classes. Pass
`-Pstore6.meeseeksJvmIntegration` to run them. Unit suites that use a scripted fake
manager, and every compile target, are green.

Track the upstream items in [Meeseeks](https://github.com/matt-ramotar/meeseeks). The
`wireJvmHost` snippet remains compile-certified only.

## Delivery and platform gaps

Activations are at-least-once. Passes are idempotent. Replays and overlapping activations
are safe.

These seam caveats apply to this adapter. The full list is in
[`mutations-drain`](../mutations-drain/README.md#process-death-and-platform-gaps).

- Durable draining needs durable `journalStorage` (`mutations-sqldelight`). The default
  in-memory journal is empty after process death.
- iOS force-quit suppresses background launches until the user opens the app. The first
  foreground `watch` or `reconcile()` recovers pending work.
- iOS foreground reconnect without a new write is not covered by OS scheduling. Call
  `runActivation` on the app-active signal.
- Android Doze and App Standby may defer constraint-met work for hours on idle devices.
  That is latency, not loss, when the journal is durable.
- Multi-process access to one journal is unsupported.

## Manual platform verification

End-to-end WorkManager and BGTask execution is not CI-gated. Run these recipes in a host
app that wires this adapter. This repository does not ship an OS-backed sample. This is
the platform-reality boundary.

### Android (not CI-gated)

1. Install a host app that wires `MeeseeksDrainScheduler` as in [Host wiring](#host-wiring).
2. Enqueue a mutation that should drain when network is available.
3. Toggle network with `adb shell svc wifi disable/enable` + `svc data`:

   ```
   adb shell svc wifi disable
   adb shell svc wifi enable
   adb shell svc data disable
   adb shell svc data enable
   ```

4. Observe the constraint-gated drain through `coordinator.events` (`DrainActivationStarted`,
   `DrainPassCompleted`) or through backend logs.

### iOS (not CI-gated)

1. Run the host app in Xcode.
2. Pause execution.
3. At the LLDB prompt:

   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchTask:@"dev.mattramotar.meeseeks.task.processing"]
   ```

4. Resume.
5. Observe the drain through `coordinator.events` or backend logs.
