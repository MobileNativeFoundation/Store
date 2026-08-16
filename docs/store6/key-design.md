# Keys and namespaces

Key design is the one thing Store asks you to get right. Everything else has a sensible default. A
key does not, because only you know how your data is shaped.

It is worth the attention because a `StoreKey` is doing two jobs at once, and they have different
consequences when you get them wrong.

## The two jobs

<!-- provenance: store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/StoreKey.kt:9-19 — the landed public interface, declarations only (KDoc elided; not a copy-paste recipe) -->

```kotlin
public interface StoreKey {
    public val namespace: StoreNamespace
    public fun canonicalId(): String
}
```

**`canonicalId()` is identity.** Two keys with the same namespace and the same canonical id are the
same key: they share one in-flight fetch, one resident value, one stale mark. Two keys with
different canonical ids share nothing. This is the lever that controls deduplication. Get it too
narrow and you fetch the same thing twice under two names. Get it too wide and two different things
collide on one cache entry.

**`namespace` is the unit of bulk operations.** It is what `invalidateNamespace` and `clearNamespace`
act on, and the durable watermark it carries covers keys the store has never even seen. This is the
lever that controls how much you can invalidate in one call.

Because identity is a `String`, the rule is simple: **the canonical id must be stable for the
lifetime of the key, and it must contain everything that makes the result different.** If two
requests would return different bytes, their canonical ids must differ.

## The smallest correct key

<!-- recipe: shapes from store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:11-21 (the landed key implementation CI compiles and runs) -->

```kotlin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}
```

One namespace per record type, the record's own identifier as the canonical id. Start here. Most
keys never need to be more than this.

## When the id needs more than an identifier

If the same record can come back differently depending on the request, the difference belongs in the
canonical id. A user record fetched with expanded relationships is not the same value as the same
user fetched without them, and it must not overwrite it.

<!-- recipe: derived from the same StoreKey contract as above; the composite-id shape follows canonicalId()'s "unique within the key's namespace" contract in StoreKey.kt -->

```kotlin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class UserKey(
    val id: String,
    val includeOrganization: Boolean = false,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String =
        if (includeOrganization) "$id+org" else id
}
```

Two things to avoid here. Do not put anything in the canonical id that changes between two requests
you *want* deduplicated, such as a timestamp, a request id, or a nonce. And do not put a secret in
it, because the canonical id is a cache key and it will be written to your source of truth.

## Choosing namespaces

Namespaces are cheap. Use one per record type as the default, and split further when you want a
smaller blast radius for bulk invalidation.

The question to ask is: *what do I want to invalidate together?* A pull-to-refresh on a user's
profile screen should invalidate the user, not everything. A sign-out should clear everything. A
"this organization's data changed" push notification is exactly the case for a per-organization
namespace, because it lets one call invalidate the right subset instead of all of it.

<!-- recipe: shapes from store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:84-147 (the landed invalidate/clear signatures); namespace-watermark behavior per StoreInvalidationConformanceTest and StoreDurableMaintenanceConformanceTest -->

```kotlin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class Document(val id: String, val title: String)

class DocumentKey(
    val organizationId: String,
    val documentId: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("documents:$organizationId")

    override fun canonicalId(): String = documentId
}

suspend fun onOrganizationChanged(
    store: Store<DocumentKey, Document>,
    organizationId: String,
) {
    store.invalidateNamespace(StoreNamespace("documents:$organizationId"))
}
```

## The payoff

Once keys are right, the namespace-level operations become the tool you reach for:

- `invalidate(key)` and `invalidateNamespace(namespace)` mark values stale without removing them.
  Active streams are signaled on return and observe refetched data, and the resident value keeps
  serving in the meantime.
- `clear(key)`, `clearNamespace(namespace)`, and `clearAll()` destructively remove values.
- The namespace and global watermarks are **durable**, so they cover keys that are not currently
  resident and survive process restart. Invalidating a namespace before a key has ever been fetched
  still makes that key's first read honest.

Which of invalidate and clear you want is its own decision, and it has its own guide:
[Invalidate or Clear](invalidate-vs-clear.md).

## Namespace equality

Store's internal key registry derives the namespace component of key identity from
`namespace.value`. The `Bookkeeper` contract likewise normalizes that component, and namespace
operations, by the same value. `StoreNamespace` does not override `equals`, so direct equality
between instances remains reference equality. Do not use that result to infer registry or
bookkeeping matches: independently constructed namespaces with the same `.value` address the same
namespace in both.

## One store or many

Namespaces partition the maintenance blast radius within one store: use them when records share a
typed Store boundary but need separate `invalidateNamespace` or `clearNamespace` scopes.

Freshness is not a store-topology choice. Each `stream` or `get` call selects its own `Freshness`
policy, so callers using one store can make different read decisions.

Use separate stores when domains need independent typed value, failure, and lifecycle boundaries.
This separation does not make operations across stores atomic; no cross-store transaction is part
of the `Store` contract.

---

*Last verified: 2026-08-10 · `main` @ `a6a156e9`, pre-6.0.0-alpha01*
