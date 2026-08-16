# Starting from Store 4

Store 4 used `com.dropbox` packages. Two release statements bound this path: Store 5's early multiplatform release states that concepts and usage were unchanged from Store 4, and the Store 5 stable release describes its additions over Store 4 as having no breaking changes. The [Store 5 translation tables](store5-to-store6.md) therefore apply to a Store 4 codebase directly. Skip the Store 5-only rows instead of migrating to Store 5 first.

## Rows that do not apply to you

Store 5 added `MutableStore`, `Validator`, fallback mechanisms, write-conflict resolution, and `NoNewData` after Store 4. A Store 4 application has none of these to translate. Per-call freshness policies and the journalled mutation path are new capabilities to evaluate, not behaviors to port.

## The rows that remain

| Store 4-era responsibility | Store 6 path |
| --- | --- |
| Fetcher | `fetcher { }`, `fetcherOfResult { }`, or the experimental seam `Fetcher` |
| Persister / SourceOfTruth | The persistence seam or a `store6-room`/`store6-sqldelight` adapter |
| Converter | No direct analog. Conversion lives in fetcher and persistence callbacks |
| Read sites | `get(key, freshness)` for a point read, `stream(key, freshness)` for an ongoing flow |

Two Store 4-era read patterns and their translations:

- `store.fresh(key)` (a Store 4 extension retained through Store 5) → `get(key, Freshness.MustBeFresh)` when the caller needs one fresh value.
- `store.stream(StoreRequest.cached(key, refresh = true))` (`StoreRequest` is Store 4's request type) → an ongoing `stream(key)` plus deliberate `invalidate(key)` when the caller requests a refresh. Not a one-call mechanical rename.

## Coexistence

Store 6 uses group `org.mobilenativefoundation.store` and packages under `org.mobilenativefoundation.store6.*`. Nothing is published before `6.0.0-alpha01`. The formal side-by-side coexistence promise begins with Store 5. No Store 4 artifact availability or interop is guaranteed. If the Store 4 dependency still resolves, keep it in place and move one complete screen at a time.
