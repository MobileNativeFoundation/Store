# mutations-conflicts

Provides canned conflict merge policies as one-line registrations inside the existing
`conflicts { }` door of `mutationStore`.

Every public entry point is `@ExperimentalStoreApi`. See [STABILITY.md](../STABILITY.md).

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
./gradlew :core:publishToMavenLocal :mutations:publishToMavenLocal :mutations-conflicts:publishToMavenLocal -Pkotlin.native.enableKlibsCrossCompilation=true
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
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:mutations-conflicts:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

The module uses Store6's full 12-target convention: Android, JVM, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `watchosArm64`, `tvosArm64`, JS, WasmJS,
`linuxX64`, and `mingwX64`.

The examples below use an application-defined `Note` with `title`, `body`, and
`updatedAtEpochMillis` fields.

## `serverWins`

`serverWins()` keeps the recaptured authoritative state for every conflict. This is
behaviorally identical to registering no merge policy.

| base | mine | theirs | Resolution |
| --- | --- | --- | --- |
| any | any | any | `ServerWins` |

```kotlin
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installServerWins() {
    conflicts {
        serverWins()
    }
}
```

## `clientWins`

`clientWins()` reasserts the same local presence after every conflict, including
`Retry(Absent)` for a deletion.

| base | mine | theirs | Resolution |
| --- | --- | --- | --- |
| any | P | any | `Retry(mine)` |
| any | A | any | `Retry(Absent)` |

A retry converges when the next generation's precondition, built from the post-barrier
recapture, matches server state at the next push. Repeated conflicts remain subject to the
unchanged-conflict bound described under [Shared semantics](#shared-semantics).

```kotlin
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installClientWins() {
    conflicts {
        clientWins()
    }
}
```

## `lastWriteWins`

`lastWriteWins()` compares the stamps returned by `writtenAt` when both sides are present.
`base` does not participate. The absent-side biases default to `THEIRS`.

| mine | theirs | Resolution |
| --- | --- | --- |
| P | P | `writtenAt(mine) > writtenAt(theirs)` resolves `Retry(mine)`; otherwise `ServerWins`. Ties go to the server. |
| A | P | `onMineAbsent`: `THEIRS` resolves `ServerWins`; `MINE` resolves `Retry(Absent)`, re-pushing the deletion. |
| P | A | `onTheirsAbsent`: `THEIRS` resolves `ServerWins`, staying deleted; `MINE` resolves `Retry(mine)`, recreating the entity. |
| A | A | `ServerWins`; nothing to contend. |

A bare `lastWriteWins { ... }` call is last-write-wins when both sides are present and
server-wins otherwise.

> **WARNING: assign a fresh stamp in every local-write projector.** A projector that copies
> the base's stamp produces stamps that never exceed the server's, and `mine` must be strictly
> newer to win, so the policy silently degrades to server-wins.

This anti-example projector copies the base's stamp — the routine `copy(title = ...)` shape —
so its stamps never exceed the server's and every conflict resolves to server-wins:

```kotlin
update(
    id = "rename-note",
    version = 1,
    codec = RenameNoteCodec,
    stales = { key, _ -> StaleSet(keys = setOf(key), namespaces = emptySet()) },
) { note, rename ->
    note.copy(title = rename.title)
}
```

The registration below writes the caller-supplied fresh stamp into each projected value.

```kotlin
private fun <K : StoreKey> noteMutators(): MutatorRegistry<K, Note> =
    mutatorRegistry {
        update(
            id = "rename-note",
            version = 1,
            codec = RenameNoteCodec,
            stales = { key, _ ->
                StaleSet(keys = setOf(key), namespaces = emptySet())
            },
        ) { note, rename ->
            note.copy(
                title = rename.title,
                updatedAtEpochMillis = rename.freshStampEpochMillis,
            )
        }
    }

private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installLastWriteWins() {
    conflicts {
        lastWriteWins { note -> note.updatedAtEpochMillis }
    }
}
```

## `threeWayMerge`

`threeWayMerge()` owns the presence matrix and delegates the both-present value merge to
the supplied function. The absent-side biases default to `THEIRS`.

| base | mine | theirs | Resolution |
| --- | --- | --- | --- |
| any | P | P | `Retry(Present(merge(baseOrNull, mine, theirs)))`, where `baseOrNull` is the base value when base is `Present`, or `null` when base is `Absent` (both sides created independently). |
| any | A | P | `onMineAbsent`: `THEIRS` resolves `ServerWins`; `MINE` resolves `Retry(Absent)`. |
| any | P | A | `onTheirsAbsent`: `THEIRS` resolves `ServerWins`; `MINE` resolves `Retry(mine)`. |
| any | A | A | `ServerWins`; nothing to contend. |

```kotlin
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installThreeWayMerge() {
    conflicts {
        threeWayMerge { base, mine, theirs ->
            theirs.copy(
                title = if (base == null || mine.title != base.title) mine.title else theirs.title,
                body = if (base == null || mine.body != base.body) mine.body else theirs.body,
                updatedAtEpochMillis =
                    maxOf(mine.updatedAtEpochMillis, theirs.updatedAtEpochMillis),
            )
        }
    }
}
```

## `mergeFields`

`mergeFields()` applies selected-field three-way merge when both sides are present. The
absent-side biases and `onBothChanged` default to `THEIRS`.

| base | mine | theirs | Resolution |
| --- | --- | --- | --- |
| any | P | P | Merge registered fields and resolve `Retry(Present(canvas))`. |
| any | A | P | `onMineAbsent`: `THEIRS` resolves `ServerWins`; `MINE` resolves `Retry(Absent)`. |
| any | P | A | `onTheirsAbsent`: `THEIRS` resolves `ServerWins`; `MINE` resolves `Retry(mine)`. |
| any | A | A | `ServerWins`; nothing to contend. |

For each registered field, `m = get(mine)`, `t = get(theirs)`, and `b = get(base)` when
base is P:

| base | Field comparison | Resolution |
| --- | --- | --- |
| P | `m == b` | Keep `t`. |
| P | `m != b && t == b` | Apply `set(canvas, m)`. |
| P | `m != b && t != b && m == t` | Keep `t`. |
| P | `m != b && t != b && m != t` | The field is contested. |
| A | `m == t` | Keep `t`. |
| A | `m != t` | The field is contested. |
| any | Contested with a field combiner | Apply `set(canvas, combine(baseOrNull, m, t))`. |
| any | Contested without a field combiner | `onBothChanged`: `THEIRS` keeps `t`; `MINE` applies `set(canvas, m)`. |

> **WARNING: register every field the application mutates locally.** `mergeFields` resolves
> every unregistered field to `theirs`. Omitting a locally mutated field silently surrenders
> that field's local edit.

This anti-example registers `title` but omits the locally mutated `body` field:

```kotlin
mergeFields {
    field(
        get = Note::title,
        set = { note, title -> note.copy(title = title) },
    )
}
```

```kotlin
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installFieldMerge() {
    conflicts {
        mergeFields {
            field(
                get = Note::title,
                set = { note, title -> note.copy(title = title) },
            )
            field(
                get = Note::body,
                set = { note, body -> note.copy(body = body) },
                combine = { _, mine, theirs -> "$mine\n$theirs" },
            )
            field(
                get = Note::updatedAtEpochMillis,
                set = { note, stamp -> note.copy(updatedAtEpochMillis = stamp) },
                combine = { _, mine, theirs -> maxOf(mine, theirs) },
            )
        }
    }
}
```

## Shared semantics

- Policies and caller-supplied `writtenAt`, `merge`, `get`, `set`, and `combine` functions
  must be pure and deterministic. A merge may execute again for the same conflict after a
  crash or rolled-back retry transaction.
- On round one, `base` is the initial frozen capture and `mine` is the projector's outcome.
  On later rounds, `base` is the recapture from the previous `Retry` and `mine` is that
  resolution's value. `theirs` is each round's fresh post-barrier recapture.
- A `Retry`-producing policy parks after three consecutive conflicted generations with the
  same server timestamp/ETag pair. Receipts with `serverMeta = null` compare equal. A server
  whose conflict metadata changes every time never trips this bound. A `ServerWins` result
  does not re-push and does not contribute another receipt.
- `ServerWins` retires the intent and terminally marks its pending invalidation effects
  `SKIPPED` (`MutationEffectSkipped`).
- An affirmative `Retry` remains a `Retry` even when its merged value equals `theirs`.
- A thrown policy parks the intent with kind `CONFLICT` and detail `"merge-failed"`.
- Every returned `Retry` value must round-trip the registered value codec. If the codec
  rejects it, the failure propagates from the drain and the intent remains retryable in
  `REFRESH_REQUIRED`.
- Do not mutate policy inputs or returned values. Inputs are engine copies, and returned
  values are encoded into the durable attempt and later decoded as equivalent copies.

## Observability

The pack adds no conflict callback. Collect the mutation store's existing advisory events and
select `MutationConflictObserved`:

```kotlin
store.events.collect { event ->
    if (event is MutationConflictObserved) {
        println("${event.mutationId}: generation ${event.generation}")
    }
}
```

Events are advisory and best-effort. They may drop under buffer pressure and replay no history
to new collectors or after restart. Use `pendingWrites()` for active intents and
`deadLetters()` for parked intents as the durable inspection record instead of treating events
as an audit log.
