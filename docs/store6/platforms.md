# Platform targets and verification

This page records declared targets, current as of 2026-09-11, and completed local checks for
`6.0.0-SNAPSHOT` during 6.0.0-alpha01 preparation, as of 2026-09-06. It does not establish a
validated release tag or Maven Central availability. The [stability policy](../../STABILITY.md)
selects fifteen libraries plus the BOM for alpha01; a successful build of a deferred library does
not add it to that roster.

## Declared library targets

The **full set** contains 12 Kotlin targets: `android`, `jvm`, `iosX64`, `iosArm64`,
`iosSimulatorArm64`, `macosArm64`, `watchosArm64`, `tvosArm64`, `linuxX64`, `mingwX64`,
`js`, and `wasmJs`. Its web tests use Node. The shared
[target convention](../../tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/Store6MultiplatformConventionPlugin.kt)
declares this set; five libraries declare smaller sets in their own builds.

| Library | Alpha01 distribution | Declared targets |
| --- | --- | --- |
| [core](../../core/build.gradle.kts) | Included | Full set |
| [testing](../../testing/build.gradle.kts) | Included | Full set |
| [sqldelight](../../sqldelight/build.gradle.kts) | Included | Full set |
| [room](../../room/build.gradle.kts) | Included | `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `watchosArm64`, `tvosArm64`, `linuxX64` |
| [compose](../../compose/build.gradle.kts) | Included | Full set |
| [graphql](../../graphql/build.gradle.kts) | Included | Full set |
| [realtime](../../realtime/build.gradle.kts) | Included | Full set |
| [mutations](../../mutations/build.gradle.kts) | Included | Full set |
| [mutations-testing](../../mutations-testing/build.gradle.kts) | Included | Full set |
| [mutations-sqldelight](../../mutations-sqldelight/build.gradle.kts) | Included | Full set |
| [devtools](../../devtools/build.gradle.kts) | Deferred | Full set |
| [devtools-inspector](../../devtools-inspector/build.gradle.kts) | Deferred | `android`, `jvm`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `js` (Node), `wasmJs` (browser) |
| [mutations-drain](../../mutations-drain/build.gradle.kts) | Deferred | Full set |
| [mutations-drain-meeseeks](../../mutations-drain-meeseeks/build.gradle.kts) | Deferred | `android`, `jvm`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `js` (Node) |
| [mutations-conflicts](../../mutations-conflicts/build.gradle.kts) | Included | Full set |
| [paging-androidx](../../paging-androidx/build.gradle.kts) | Included | Full set except `iosX64` |
| [file](../../file/build.gradle.kts) | Included | Full set |
| [ktor](../../ktor/build.gradle.kts) | Included | Full set |
| [opentelemetry](../../opentelemetry/build.gradle.kts) | Included | `android`, `jvm` |

The [BOM](../../bom/build.gradle.kts) constrains the fifteen included libraries and has no runtime
target of its own. The [publication manifest](../../.github/release-manifest.json) is the release
allowlist. Samples, demos, API-dump projects, and `extension-probe` are verification projects,
not additional libraries in this table.

`watchosArm64` and `tvosArm64` are device targets. No watchOS or tvOS simulator target is
declared here. Their KLIBs do not demonstrate execution on a watch, television, or simulator.
Room has no Intel iOS, MinGW, JS, or Wasm target; the inspector uses a browser for Wasm rather
than the full convention's Node environment.

## Completed local verification

An **executed test** ran test cases and produced results. **Compile/artifact only** means a
compiler or publication check completed without proving target runtime behavior. **Skipped**
means the test task did not execute. Counts below combine completed checks with the later
mutation fixture checks; they are not a single release-tag certification.

| Target or check | Completed local evidence | Boundary |
| --- | --- | --- |
| JVM | All 19 libraries plus `extension-probe`: 20 test tasks, 1,968 tests passed. The latest mutation check passed 305 tests. | Default JVM suite; the full mutation model-checking release lane is separate. |
| JS Node | 15 libraries plus `extension-probe`: 16 test tasks, 1,534 tests passed, including 298 mutation tests. | `sqldelight` and `mutations-sqldelight` web test tasks were skipped; Room and OpenTelemetry declare no JS target. |
| Wasm Node | 13 libraries plus `extension-probe`: 14 test tasks, 1,506 tests passed, including 298 mutation tests. | Both SQLDelight modules' web test tasks were skipped. The inspector uses a browser; Meeseeks, Room, and OpenTelemetry declare no Wasm target. |
| Wasm browser | `devtools-inspector`: 14 tests passed. | This is the inspector's configured browser test target, not a browser run of every library. |
| iOS Simulator arm64 and macOS arm64 | All 37 declared library/probe test tasks for these two targets passed: 3,764 tests. Mutations passed 298 on each target. | Meeseeks has no macOS target; OpenTelemetry has neither target. This does not cover Intel iOS or physical devices. |
| Native stress on macOS arm64 | Nine tests across four classes passed, covering eviction, invalidation, closure, and backpressure. | A separate executed stress selection, not additional platform coverage. |
| Android | Library publication produced AARs; an independent Android consumer compiled successfully. | Compile/artifact only; no Android device or instrumentation-test execution is established here. |
| Other declared Native targets | Local publication produced the expected target KLIBs. | Artifact only for `iosArm64`, `iosX64`, `watchosArm64`, `tvosArm64`, `linuxX64`, and `mingwX64`; no device or hosted Linux/Windows runtime result is claimed. |

The SQLDelight adapters attach their SQL driver tests to JVM/Android unit and selected Native
source sets. A declared JS or Wasm target and a published KLIB therefore do not establish SQL
runtime coverage on the web.

Local publication and inventory validation checked 223 publication entries across all 19
libraries plus the BOM, including the expected consumable file, POM, and Gradle module metadata.
That inventory includes deferred libraries. Native KLIB cross-compilation was enabled;
artifact existence does not establish execution or signed Central deployment.

## Toolchains and independent consumers

The local host was macOS arm64, with JDK 17.0.18, Xcode 26.6, and Swift 6.3.3. The repository
pins Kotlin 2.3.20 and Gradle 8.11.1. These identify the checked environment, not a claim that
every newer or older toolchain works.

The producer's shared Android convention uses `compileSdk = 36`, `minSdk = 24`, and Java 11
bytecode. Most JVM libraries also target Java 11; `mutations-drain-meeseeks` requires a JVM
Java 17 toolchain because of its Meeseeks dependency.

The independent Android consumer was checked with Kotlin 2.3.20, AGP 8.10.0, JDK 17,
`compileSdk = 34`, `minSdk = 24`, and JVM/Java bytecode target 11. Its successful compilation
does not lower the producer's SDK requirement or establish Android runtime coverage.

| Independent consumer | Completed evidence | Boundary |
| --- | --- | --- |
| JVM with BOM | Resolved ten of the alpha01 libraries and ran using published local coordinates. | No repository project dependencies; not Central resolution. The five libraries added to the roster after this check are not in that graph. |
| Store 5 coexistence | Ran with Store6 core/BOM and `store5:5.1.0-alpha10`. | This checks that selected pair, not every Store 5 release. |
| Android | Compiled with core, Room, and SQLDelight using the toolchain above. | No device execution. |
| Room walkthrough | Generated database code with Room 3.0.0/KSP 2.3.10 and ran the JVM walkthrough. | Independent artifact consumer of the adapter and core. |
| Native | One core-consumer test passed on macOS arm64 and one on iOS Simulator arm64; `iosArm64` consumer source compiled. | Device compilation does not establish device execution. |

Checksum verification matched the resolved Store6 archives with the isolated publication in
seven selected dependency graphs: JVM, Store 5 coexistence, Android, the Room walkthrough,
macOS arm64, iOS Simulator arm64, and iOS arm64. The JVM and coexistence graphs also retained
the selected BOM. These checks cover the selected configurations, not every possible consumer
dependency graph.

## Swift facade: deferred distribution

The [Swift facade](../../store6-swift/README.md) remains separate from the Maven library roster.
Its local XCFramework declares `iosArm64`, `iosSimulatorArm64`, `iosX64`, and `macosArm64`;
the [Swift package](../../Package.swift) supports iOS 15 and macOS 12 or later. It declares no
watchOS or tvOS product.

Two complete generations produced identical hashes for eight API files across five surfaces:
core Objective-C, core SKIE, mutations Objective-C, mutations SKIE, and the facade's SKIE export.
The local facade checks passed six Kotlin/Native tests on macOS arm64 and 24 Swift package tests
against the assembled debug XCFramework.

Those successful checks do not cover all exception paths. Some raw Kotlin maintenance,
acknowledgement/status, and closed-Store failures lack declared Swift error conversion and can
terminate the process. Swift `async throws` alone does not make them catchable. Checked wrappers
and process-survival tests remain prerequisites for distribution; see the facade's
[boundaries](../../store6-swift/README.md#boundaries).

## Pending release verification

| Check | Status |
| --- | --- |
| Clean root build and coverage | Passed locally on macOS arm64; test results include cache reuse. Android lint emitted Kotlin metadata-version diagnostics despite successful tasks, so complete Kotlin lint analysis is not established. |
| Hosted Linux runtime verification | Pending; local cross-compiled artifacts are not a substitute |
| Full mutation model-checking release lane | Pending; default JVM counts above exclude it |
| Complete validation bound to the eventual release tag | Pending |
| Signed Central publication and resolution of released artifacts | Pending |

The [release procedure](../../RELEASING.md) defines the required matrix, the single sharded full
mutation suite execution, artifact checks, and evidence tied to the release source and workflow
attempt.
