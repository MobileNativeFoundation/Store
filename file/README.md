# Store6 file

`file` persists one file per canonical key for blob or text values, using kotlinx-io.

This artifact is experimental. Every public entry point is `@ExperimentalStoreApi`. The Store6
seam is a **freeze candidate, not frozen** — see [STABILITY.md](../STABILITY.md).

## Install

This artifact ships in 6.0.0-alpha01, which is not released yet. The coordinates below are
for a local publication from this source tree; nothing reaches Maven Central before that
release.

Use JDK 17 and configure an Android SDK containing platform 36 through `sdk.dir` in
`local.properties` or `ANDROID_HOME`. The commands below enable Native KLIB cross-compilation;
publication of those files does not establish Native execution. Apple execution and linking
require macOS with Xcode.

From the repository root, publish the module and its Store6 dependencies locally:

```bash
./gradlew :core:publishToMavenLocal :file:publishToMavenLocal -Pkotlin.native.enableKlibsCrossCompilation=true
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

Use the same Store6 version for `core` and `file`:

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            languageSettings.optIn(
                "org.mobilenativefoundation.store6.core.ExperimentalStoreApi",
            )
            dependencies {
                implementation("org.mobilenativefoundation.store:core:6.0.0-SNAPSHOT")
                implementation("org.mobilenativefoundation.store:file:6.0.0-SNAPSHOT")
            }
        }
    }
}
```

Constructors take `kotlinx.io.files.Path`. Codecs take `kotlinx.io.Source` and `kotlinx.io.Sink`.
This artifact depends on kotlinx-io 0.9.1.

## Entry points

Construct `FileSourceOfTruth` and `FileBookkeeper`, then pass them to `store { }`. Construction
performs no filesystem IO. The first operation creates that instance's subtrees.

```kotlin
@file:OptIn(ExperimentalStoreApi::class)

val directory = Path("/data/store6")
val notes =
    store<NoteKey, String> {
        fetcher { key -> api.getNote(key.id) }
        persistence(
            FileSourceOfTruth(
                directory = directory,
                codec = Utf8StringFileCodec,
                corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                ioContext = Dispatchers.Default,
            ),
        )
        bookkeeper(
            FileBookkeeper(
                directory = directory,
                ioContext = Dispatchers.Default,
            ),
        )
    }
```

`Utf8StringFileCodec` encodes text. `ByteArrayFileCodec` encodes blobs. For kotlinx-serialization
values, paste the recipe below.

The same `directory` may back both classes. `FileSourceOfTruth` owns `values/`, `values-tmp/`, and
`values-trash/`. `FileBookkeeper` owns `bookkeeping/`, `bookkeeping-tmp/`, and `bookkeeping-trash/`.
The adapter strips any `Job` from `ioContext` so a caller-provided job cannot re-parent internal
work. `ioContext` defaults to `Dispatchers.Default`. `corruptionPolicy` defaults to
`FileCorruptionPolicy.QUARANTINE`.

## Targets

The module uses Store6's full 12-target convention: android, jvm, iosX64, iosArm64,
iosSimulatorArm64, macosArm64, watchosArm64, tvosArm64, linuxX64, mingwX64, js/Node, and
wasmJs/Node.

## Semantics and limits

**Durability.** Readers observe either the previous complete file or the new complete file, never
a mix (atomic visibility). A mutation that returned before a process crash is visible after
restart (process-crash durability). This adapter does not fsync. After an OS crash or power loss,
a returned mutation may be undone: the previous complete row or absence reappears, or the new
file exists with damaged content. Detected corruption is treated as absence and the store
refetches. An undone rename is undetectable and surfaces as the earlier committed state.

**One live instance per directory.** Concurrent `FileSourceOfTruth` instances over one directory
are unsupported, and the same rule applies to `FileBookkeeper`. Reader signals are instance-scoped.
There is no cross-instance signal bridging. Changes made through another instance appear in a new
collection's first emission and are not announced to an already-active collection. One
`FileSourceOfTruth` plus one `FileBookkeeper` on the same directory is supported because their
subtrees are disjoint.

**Key limits.** `namespace.value` and `canonicalId()` each must be at most 159 UTF-8 bytes.
A longer component throws `IllegalArgumentException` naming the offending part, the limit, and
the actual length. Empty strings are valid. On disk they use the `"0"` sentinel so they do not
collapse onto a parent directory. Windows `MAX_PATH` (260 characters) can still be exceeded by a
deep `directory` plus two encoded components. Choose a shallow root on Windows. This adapter does
not detect that overflow.

Components must also be well-formed UTF-16. A component holding an unpaired surrogate throws
`IllegalArgumentException` naming the offending part and the index of that surrogate, before any
file or mirror change: UTF-8 encoding replaces an unpaired surrogate with U+FFFD, so distinct
malformed strings would otherwise map to one on-disk name while staying distinct Store identities.

**Cold bookkeeping recovery.** `status` reads storage only on its first-operation recovery. A
failed recovery propagates as a typed persistence failure, never as `null` or as fresh
metadata, and leaves the mirror uninitialized so a later call retries.

**Absent paths.** A path that does not exist is absence, never an error and never corruption.

**Corruption handling.** Value files are a versioned envelope with a CRC32 of the payload. A
truncated file, wrong magic, unknown version, length mismatch, or CRC mismatch is structural
corruption. `FileCorruptionPolicy.QUARANTINE` (the default) moves the unreadable file aside as
`.corrupt` (best-effort) and treats the row as absent. `FileCorruptionPolicy.PROPAGATE` throws
from the reading operation. Reader collections then follow the engine's retry contract.

**No `TransactionalSourceOfTruth`.** This adapter is not a `TransactionalSourceOfTruth`, so
integrations that engage only over that interface treat it as non-transactional. A store that
combines `mutations` with this source of truth has the same non-transactional posture as any
non-transactional source of truth.

**Android API 24–25.** kotlinx-io `atomicMove` is unsupported on API 24–25. The adapter falls
back to `android.system.Os.rename` (`rename(2)`).

**mingwX64.** The MinGW kotlinx-io implementation uses ANSI Win32 file APIs. A caller-provided
root containing characters outside the active code page can mis-encode. Adapter-generated names
are ASCII.

**js/wasmJs.** On js/Node and wasmJs/Node, kotlinx-io's Node filesystem calls are synchronous.
Each operation briefly blocks the event loop.

## kotlinx-serialization codec

This artifact does not ship a kotlinx-serialization codec. When you need JSON values, paste:

```kotlin
class JsonFileCodec<V : Any>(private val serializer: KSerializer<V>) : FileCodec<V> {
    override fun encode(value: V, sink: Sink) =
        sink.writeString(Json.encodeToString(serializer, value))
    override fun decode(source: Source): V =
        Json.decodeFromString(serializer, source.readString())
}
```

The recipe needs kotlinx-serialization-json on the classpath.

## Testing your wiring

Add `org.mobilenativefoundation.store:testing:6.0.0-SNAPSHOT` to the test source set. Extend
`SourceOfTruthContractKit<K, V>` for your `FileSourceOfTruth` fixture and `BookkeeperContractKit`
for your `FileBookkeeper` fixture. Return a fresh directory-backed adapter from each factory
method.

The inherited suites cover 15 source-of-truth contracts and 6 bookkeeping contracts on every
target where you execute them. Their close and lifecycle semantics are final.

## Sample

```shell
./gradlew :file-sample:run
```

The program checks four observable behaviors and exits nonzero if any check fails:

1. The first fetch persists. A second read in the same process is served without another fetch.
2. A rebuilt store over the same directory serves that row from the source of truth without a
   refetch, because the value and bookkeeper metadata are on disk.
3. `invalidateNamespace` survives another rebuild and forces a refetch.
4. A structurally corrupt value file is treated as absence and refetched.
