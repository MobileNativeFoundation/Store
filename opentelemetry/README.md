# opentelemetry

An OpenTelemetry sink for Store6 over the Store telemetry seam. The single public class,
`OpenTelemetryStoreTelemetry`, records metrics for every telemetry hook and, opt-in,
synthesizes one span per settled fetch. Every public entry point is `@ExperimentalStoreApi`.
The seam is a freeze candidate, not frozen.

## Install

This artifact ships in 6.0.0-alpha01, which is not released yet. The coordinates below are
for a local publication from this source tree; nothing reaches Maven Central before that
release.

Use JDK 17 and configure an Android SDK containing platform 36 through `sdk.dir` in
`local.properties` or `ANDROID_HOME`. The commands below enable Native KLIB cross-compilation;
`:core` publishes Native KLIBs while this module is JVM and Android only, and publication of
those files does not establish Native execution. Apple execution and linking require macOS with
Xcode.

From the repository root, publish the module and its Store6 dependencies locally:

```bash
./gradlew :core:publishToMavenLocal :opentelemetry:publishToMavenLocal -Pkotlin.native.enableKlibsCrossCompilation=true
```

Use the version in `gradle.properties` (currently `6.0.0-SNAPSHOT`) and add the local repository
to the consuming build's dependency repositories:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    google()
}
```

```kotlin
dependencies {
    implementation("org.mobilenativefoundation.store:opentelemetry:6.0.0-SNAPSHOT")
}
```

Opt in to `ExperimentalStoreApi`, construct the sink from the app's `OpenTelemetry`, and add
one builder line:

```kotlin
import org.mobilenativefoundation.store6.opentelemetry.OpenTelemetryStoreTelemetry

val users = store<UserKey, User> {
    fetcher(userFetcher)
    telemetry(OpenTelemetryStoreTelemetry(openTelemetry))
}
```

To install it beside the devtools sinks, compose with `storeTelemetryOf` from the `devtools`
artifact: `telemetry(storeTelemetryOf(logger, monitor, otelSink))`. Neither artifact depends
on the other.

Construction reads the meter and tracer providers from the passed `OpenTelemetry` once:
construct the sink after the app's SDK is built. `OpenTelemetry.noop()` is accepted and
records nothing; there is no global lookup and no install-time detection of a missing SDK.

## Targets

`androidTarget` (minSdk 24, the Store6 convention) and `jvm` only: the sink builds on
`opentelemetry-java`, which publishes JVM bytecode. The other Store6 targets keep the
telemetry seam and the devtools sinks; they gain an OpenTelemetry bridge when a multiplatform
OpenTelemetry API with stable metrics exists.

Android consumers inherit opentelemetry-java's requirements: API level 23+ with core-library
desugaring (opentelemetry-java `VERSIONING.md`). For `minSdk` below 26, opentelemetry-android's
guidance additionally applies: enable `isCoreLibraryDesugaringEnabled`, depend on a current
`desugar_jdk_libs`, use AGP 8.3.0+, and set
`android.useFullClasspathForDexingTransform=true` in `gradle.properties`. This artifact is
API-only and ships no R8 keep rules; the app's OpenTelemetry SDK carries its own.

`opentelemetry-api` (Apache-2.0, like Store) arrives at `api` scope together with its
`opentelemetry-context` dependency. Apps using an OpenTelemetry BOM should align versions
through the BOM; this artifact's pin is a floor, not a mandate.

## Signals

[SIGNALS.md](SIGNALS.md) defines the v0 vocabulary in full. Summary:

| Signal | Name | Recorded on |
| --- | --- | --- |
| Counter `{attempt}` | `store6.fetch.attempts` | fetch start |
| Histogram `s` | `store6.fetch.duration` | fetch success/failure (`error.type` on failure) |
| Counter `{serve}` | `store6.serves` | every public serve (`store6.origin`) |
| Counter `{invalidation}` | `store6.invalidations` | invalidation |
| Counter `{clear}` | `store6.clears` | clear |
| Span (opt-in) | `store6.fetch` | fetch success/failure |

The histogram unit is seconds; the devtools v0 logger logs `fetch_ms` milliseconds — do not
mix them in one dashboard query.

Fetch spans are standalone, synthesized after the fetch settles: they never join a caller
trace and never parent the fetcher's own HTTP spans. Enable them with `emitSpans = true` for
per-fetch inspection; add `keyAttributeOnSpans = true` to attach `store6.key`. Keys never
appear on metrics.

## Identity, values, and diagnostics

Stored values never cross the telemetry seam. This sink additionally never emits
`StoreError` messages or causes — failures carry only the variant name in `error.type` — and
never records exceptions on spans. Namespaces and (opt-in) key canonical ids are the only
identity that leaves the process; if namespaces or keys embed tenant or user identifiers,
treat span export accordingly.

## Installed cost and zero-cost boundary

Each hook performs one concurrent-map read (after a namespace's first event), one instrument
`add`/`record` into the app's SDK, and — for spans — one span build with two timestamps.
Attribute sets are interned per namespace, bounded by `maxNamespaces` (default 512; beyond
it, further namespaces coalesce into `store6.namespace = "overflow"`). After construction,
hooks never throw; failures inside the OpenTelemetry implementation are discarded.

Hooks stay non-blocking only if the SDK configuration is. Samplers run inside span start and
span processors inside span end, synchronously on the calling thread — use batching span
processors and prompt samplers; a synchronous exporter inside a span processor stalls Store
engine threads. Metric recording writes synchronously into the SDK's aggregation storage
(bounded work); metric collection and export run on the reader's own schedule. To silence one
instrument (for example `store6.serves` on a hot store), register a view when building the
meter provider:

```kotlin
SdkMeterProvider.builder()
    .registerView(
        InstrumentSelector.builder().setName("store6.serves").build(),
        View.builder().setAggregation(Aggregation.drop()).build(),
    )
```

Leaving `telemetry` unset preserves the core engine's null fast path.
