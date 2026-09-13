# Quickstart

> Store 6 is in development; **alpha artifacts are not yet available from Maven Central**. This page describes the API as
> it stands on `store6`; the install coordinates land with 6.0.0-alpha01.

Store needs two things from you: a **key** that identifies what you want, and a **fetcher** that
knows how to fetch it. Store shares in-flight requests, serves resident values, and tracks
staleness. `maxIdleKeys` bounds idle engine residency. The default in-memory source of truth and
bookkeeper retain entries for distinct keys for the Store's lifetime; configure persistent
implementations when key cardinality can grow without limit.

Here is the whole idea in five lines.

<!-- display: store block verbatim from quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:50-52, dedent 8 (parity-checked); the stream and get lines are display forms, shapes from Main.kt:55-65, NOT parity-checked -->

```kotlin
val users = store<UserKey, User> {
    fetcher { key -> FakeApi.getUser(key.id) }
}

users.stream(UserKey("1")).collect { result -> render(result) }
val user = users.get(UserKey("2"))
```

The `store { }` block is verbatim from a module this repository compiles and runs in CI. The last
two lines are shown in their simplest form so the shape is legible. The program below is the exact
one CI executes, and it is where the real `stream` and `get` call sites live.

## The whole program

**This exact program compiles and runs on every pull request.** It is the `quickstart`
module, executed by the `./gradlew :quickstart:run` step in
[`.github/workflows/store6.yml`](../../.github/workflows/store6.yml). If it broke, this page
would not be shipping.

Supporting declarations — the key, the model, and a stand-in service:

<!-- verbatim: quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:1-39, dedent 0 (parity-checked) -->

```kotlin
package org.mobilenativefoundation.store6.quickstart

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store

/** Identifies a user by the stable identifier used by the example service. */
private class UserKey(
    /** The user identifier passed to the example service. */
    val id: String,
) : StoreKey {
    /** The namespace shared by user records in the example store. */
    override val namespace: StoreNamespace = StoreNamespace("users")

    /** Returns the service identifier used to distinguish this user from other users. */
    override fun canonicalId(): String = id
}

/** A user record returned by the example service. */
private class User(
    /** The stable identifier assigned to this user. */
    val id: String,

    /** The display name returned by the example service. */
    val name: String,
)

/** Provides deterministic user data for the executable example. */
private object FakeApi {
    /** Returns a user after simulating an asynchronous service call. */
    suspend fun getUser(id: String): User {
        delay(100)
        return User(id, "User $id")
    }
}
```

A `StoreKey` gives Store two things: a `namespace`, which groups related records so you can
invalidate or clear them together, and a `canonicalId()`, which distinguishes one record from
another inside that namespace. Key design is the one skill Store asks you to learn, and it has its
own guide: [Keys and Namespaces](key-design.md).

And `main`:

<!-- verbatim: quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:47-67, dedent 0, omit docs:snippet marker comments (parity-checked) -->

```kotlin
public fun main(): Unit =
    runBlocking {
        val users = store<UserKey, User> {
            fetcher { key -> FakeApi.getUser(key.id) }
        }

        users.stream(UserKey("1")).take(2).collect { result ->
            when (result) {
                is StoreResult.Loading -> println("Loading…")
                is StoreResult.Data -> println("Data(name=${result.value.name}, origin=${result.origin})")
                is StoreResult.Revalidated -> println("Revalidated(age=${result.age})")
                is StoreResult.Error -> println("Error(${result.error})")
            }
        }
        println("get: ${users.get(UserKey("2")).name}")
        users.close()
    }
```

## Reading the output

`stream` gives you a `StoreResult`, and there are exactly four kinds. Handle all four and there is
no fifth case waiting to surprise you:

- **`Loading`** — demand has been registered and no value is available yet.
- **`Data`** — a value, carrying an `origin` that tells you where it came from (`FETCHER`, `SOT`,
  `MEMORY`, `OVERLAY`) and whether it is stale or refreshing. The example prints the origin because
  attribution honesty is a contract, not a debugging aid.
- **`Revalidated`** — the server said nothing changed. You get one of these with the resident value's
  age, rather than a redundant `Data` frame.
- **`Error`** — a fetch or persistence operation failed. Whether a resident value is served before
  the error depends on the read policy and the available freshness evidence.

One detail worth naming so it does not read as magic: **`take(2)` is what ends this program.**
`stream` is an unbounded flow that stays live for as long as you collect it. The example takes the
first two frames — `Loading`, then `Data` — and stops. In an app you collect for the lifetime of the
screen instead, and `close()` the store when you are done with it.

Continue with [the read contract](/docs/store6/concepts/read-contract) for result and failure
semantics, then [freshness policies](/docs/store6/concepts/freshness) for choosing how each read
uses resident and fetched data.

## Write path (experimental)

> **Experimental.** `mutations` is a separate artifact and every public symbol is
> `@ExperimentalStoreApi`. It is in the 6.0.0-alpha01 roster; the artifact is not yet available from Maven Central.
>
> **The spelling below is the current API surface.** The module is still experimental — shapes
> can change in any release — but the snippet below matches the implementation.

Optimistic writes go through a journal and can be queued while offline. The default journal is
in memory; process-death recovery requires durable journal storage, such as
[`mutations-sqldelight`](../../mutations-sqldelight/). A mutation store implements
`Store`, so the read operations above still work.

<!-- Source anchors: MutationStore.kt (mutationStore factory), MutatorRegistry.kt (sugars),
MutationsWalkingSkeletonTest.kt (the end-to-end tracer). -->

```kotlin
@OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
val users = mutationStore(
    registry = registry,
    server = server,
    // Restart-safe key recovery is compile-time required. For keys reconstructible from the
    // identity pair, the resolver is one line:
    keyResolver = MutationKeyResolver { identity -> UserKey(identity.canonicalId) },
    valueCodecVersion = 1,
    valueCodec = userJsonCodec,
) {
    fetcher { key -> api.load(key) }
}

users.mutate(key, renameRef, Rename("new name"))   // journalled — the only write path
users.drain(key)                                   // push pending intents and adopt each ack
```

The flow, end to end:

1. **Offline enqueue.** `mutate` appends one intent and returns a mutation id. Nothing is pushed.
2. **Optimistic visibility.** `stream(key)` emits `Data(value = optimistic, origin = OVERLAY)`.
3. **Reconnect and acknowledge.** `drain(key)` pushes the pending intents and adopts each ack. For OS-scheduled background draining, see [`mutations-drain`](../../mutations-drain/README.md).
4. **Confirmed.** The server's echo becomes the committed value. An active collector that observes
   proven source adoption stops applying the acknowledged optimistic head; queued later writes
   remain projected over the echo. The journal retains recovery data until retirement. Adoption
   does not fetch the echo again, but later invalidations or lost freshness evidence after restart,
   eviction, or a canonical-key change can still cause the read policy to refresh.

Two properties that are design decisions rather than accidents:

- **`runtime()` returns `null` on a mutation store, by design.** That withholds the raw write handle,
  which is the library-granted way to write around the journal. Every consumer write stays
  journalled, and there is no second path that could commit a value the journal never saw.
- **A pending write is `origin == OVERLAY`, not `isStale`.** `isStale` is never set on an overlay
  frame, because an optimistic value genuinely is new. Drive a "saving…" indicator off the origin and
  narrate the `OVERLAY` → `SOT` flip. See [the stability policy](../../STABILITY.md#9-reading-pending-writes-and-staleness)
  for the full consumer guidance, and
  note that `get` is unprojected: overlays apply only to `stream`.

The alpha records `ACKED` durably before adopting the server echo, then retires the journal row
after adoption and effects. Recovery from durable `ACKED` resumes those steps without another
push. A crash before the acknowledgement receipt is durable can cause the same idempotency key
to be sent again. This process-death recovery requires durable journal storage. See
[the stability policy](../../STABILITY.md#mutations).

---

The runnable examples live in this checkout's `quickstart` and `mutations-quickstart` modules.
