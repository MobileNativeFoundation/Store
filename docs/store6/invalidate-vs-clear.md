# Invalidate or clear

Both make a value go away. They are not interchangeable, and picking the wrong one produces one of
two bugs: a spinner where the user expected content, or stale content where the user expected a
spinner.

The one-line version:

- **`invalidate` marks stale.** The value stays, keeps being served, and gets refreshed.
- **`clear` removes.** The value is gone, and the next read starts from nothing.

## What invalidate does

`invalidate(key)` marks the value stale without removing it. On return, active streams of that key
have been signaled and will observe refetched data, and the resident value keeps being served as
stale in the meantime.

Three properties are worth knowing because they are what make it safe to call:

- **The stale mark is durable.** It survives process restart until a later successful fetch or
  revalidation clears it.
- **It is level-triggered monotone state**, so a signal issued during any race window is never lost.
  You do not have to reason about whether a fetch was in flight when you called it.
- **A live collector observes the refetch**, not a gap. This holds under load: a burst of 10,000
  invalidations converges without losing the final staleness.

What the user sees: the content stays on screen, and updates in place when the fetch lands. That is
the stale-while-revalidate shape, and it is what you want for pull-to-refresh, for a "data changed"
push, and for anything where showing the previous answer beats showing nothing.

<!-- recipe: shapes from core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:70-98 (landed invalidate/invalidateNamespace signatures and their contracts); behavior per StoreInvalidationConformanceTest.invalidate_activeStream_observesRefetchedData -->

```kotlin
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

class User(val id: String, val name: String)

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

// Pull-to-refresh: the current value stays on screen while the refresh runs.
suspend fun onPullToRefresh(
    store: Store<UserKey, User>,
    key: UserKey,
) {
    store.invalidate(key)
}

// The screen's collector needs no special case. It receives the stale value with
// isStale = true and refreshing = true, then the fresh one.
fun observeUser(
    store: Store<UserKey, User>,
    key: UserKey,
): Flow<StoreResult<User>> = store.stream(key)
```

## What clear does

`clear(key)` destructively removes the value. On return the resident value is gone: active streams
observe the absent-value transition (a `Loading` frame) and then refetched data. Removal includes
the configured source-of-truth row and its freshness bookkeeping.

Two properties matter here:

- **An in-flight fetch that started before the clear can no longer commit.** Its waiters observe
  `StoreError.Missing`. A clear racing a fetch cannot resurrect the discarded value.
- **A post-clear stream never replays pre-clear data.** It starts absent or loading. This is a
  guarantee, not a timing accident, and it is what makes clear safe for sign-out.

What the user sees: the content disappears and a loading state appears. That is correct when the old
value is not just outdated but *wrong to show* — a different user's data, data the current session is
no longer entitled to, a record the server says no longer exists.

<!-- recipe: shapes from core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:113-164 (landed clear/clearNamespace/clearAll signatures and their contracts); behavior per StoreInvalidationConformanceTest.clear_thenNewStreamEmitsLoadingNeverStaleReplay -->

```kotlin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class User(val id: String, val name: String)

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

class Document(val id: String, val title: String)

class DocumentKey(
    val organizationId: String,
    val documentId: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("documents:$organizationId")

    override fun canonicalId(): String = documentId
}

// Sign-out: the previous session's data must not be shown again, ever.
suspend fun onSignOut(store: Store<UserKey, User>) {
    store.clearAll()
}

// A record the server reported as deleted: remove it rather than refreshing it.
suspend fun onRecordDeleted(
    store: Store<UserKey, User>,
    key: UserKey,
) {
    store.clear(key)
}

// One tenant's data is no longer valid, the rest is fine.
suspend fun onOrganizationRevoked(
    store: Store<DocumentKey, Document>,
    organizationId: String,
) {
    store.clearNamespace(StoreNamespace("documents:$organizationId"))
}
```

## Choosing

| You want to say | Use | The user sees |
|---|---|---|
| "This might be out of date, go check" | `invalidate` | Content stays, updates in place |
| "This is no longer valid to show" | `clear` | Content disappears, then loads |
| "Everything for this tenant is suspect" | `invalidateNamespace` | Each affected screen refreshes in place |
| "Everything for this tenant is revoked" | `clearNamespace` | Each affected screen empties, then loads |
| "Sign out" | `clearAll` | Everything empties |

The test that decides it: **would showing the old value for another few hundred milliseconds be
wrong, or merely imperfect?** Wrong means clear. Imperfect means invalidate.

## The stale-while-revalidate consequence

This is where the two diverge most visibly.

After `invalidate`, the next read serves the stale resident value **immediately** and refreshes in
the background. The refresh produces exactly one terminal outcome: one fresh `Data`, or one
served-stale `Error` if the fetch fails, or one `Revalidated(age)` if the server says nothing
changed. Never two. If the fetch fails, the user still has the old content and an error, rather than
an empty screen.

After `clear`, there is nothing to serve. The next read is a cold read: `Loading`, then whatever the
fetcher returns. If the fetch fails, the user has an empty screen and an error.

That asymmetry is the whole decision. `invalidate` degrades gracefully when the network is bad.
`clear` does not, because it cannot — you told it the old value was not safe to show.

---

*Last verified: 2026-07-26 · `main` @ `c4fbaf4`, pre-6.0.0-alpha01*
