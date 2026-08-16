# Quickstart

> Store 6 is in development and **nothing is published yet**. This page is the shape of the API as
> it stands on `main`; the install coordinates land with 6.0.0-alpha01.

Store needs two things from you: a **key** that identifies what you want, and a **fetcher** that
knows how to go get it. Everything else — sharing one in-flight request across concurrent callers,
serving what is already resident, tracking staleness, bounding memory — is what Store does with
those two things.

Here is the whole idea in five lines.

<!-- display: store block verbatim from quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:49-51, dedent 8 (parity-checked); the stream and get lines are display forms, shapes from Main.kt:53-62, NOT parity-checked -->

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

<!-- verbatim: quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:47-63, dedent 0 (parity-checked) -->

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
- **`Error`** — the fetch failed. If a stale value was resident, you will have been served it first.

One detail worth naming so it does not read as magic: **`take(2)` is what ends this program.**
`stream` is an unbounded flow that stays live for as long as you collect it. The example takes the
first two frames — `Loading`, then `Data` — and stops. In an app you collect for the lifetime of the
screen instead, and `close()` the store when you are done with it.

Continue with [the read contract](/docs/store6/concepts/read-contract) for result and failure
semantics, then [freshness policies](/docs/store6/concepts/freshness) for choosing how each read
uses resident and fetched data.

## Write path (experimental)

> **Experimental.** `mutations` is a separate artifact and every public symbol is
> `@ExperimentalStoreApi`. It ships **with** 6.0.0-alpha01 — nothing here is published yet.
>
> **The spelling below is the current API surface.** The module is still experimental — shapes
> can change in any release — but the snippet below matches the implementation.

Optimistic writes go through a journal, so they survive being offline and survive process death.
You get a mutation store instead of a plain one, and it is a `Store` — everything above still works.

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
3. **Reconnect and acknowledge.** `drain(key)` pushes the pending intents and adopts each ack.
4. **Confirmed.** By the acknowledgement contract, the server's echo becomes the committed value,
   attributed `SOT` or `MEMORY`, and the optimistic frame is retired rather than replayed. A stream
   opened after the acknowledgement sees the echo. Convergence for a collector that was *already*
   active across the acknowledgement is the subject of open engine work and is not yet a behavior
   this page will promise. No redundant fetch happens anywhere in this sequence.

Two properties that are design decisions rather than accidents:

- **`runtime()` returns `null` on a mutation store, by design.** That withholds the raw write handle,
  which is the library-granted way to write around the journal. Every consumer write stays
  journalled, and there is no second path that could commit a value the journal never saw.
- **A pending write is `origin == OVERLAY`, not `isStale`.** `isStale` is never set on an overlay
  frame, because an optimistic value genuinely is new. Drive a "saving…" indicator off the origin and
  narrate the `OVERLAY` → `SOT` flip. See [the stability policy](../../STABILITY.md#9-reading-pending-writes-and-staleness)
  for the full consumer guidance, and
  note that `get` is unprojected: overlays apply only to `stream`.

The alpha ships a two-step durable acknowledgement path, which means a crash in the acknowledgement
window leaves a replayable pending intent rather than losing your write, at the cost of the same
push possibly being re-sent. That tradeoff is stated in full in
[the stability policy](../../STABILITY.md#mutations).

---

*Last verified: 2026-08-10 · `main` @ `a6a156e9`, pre-6.0.0-alpha01*
