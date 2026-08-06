package org.mobilenativefoundation.store6.core.seam

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Projects confirmed stream residence through one engine-owned writer per key.
 *
 * [apply] receives the latest confirmed value, or `null` for confirmed absence. Returning that
 * same value by equality preserves its envelope, origin, age, staleness, and refresh state.
 * Returning a different non-null value emits it from the overlay with zero age and without
 * staleness; its `refreshing` flag always reflects the live fetch slot. Returning `null` exposes
 * the normal absent/loading transition. Consequently a
 * non-null result over a null base is an optimistic create, while a null result over a non-null
 * base is an optimistic delete. `Store.get` is intentionally not projected.
 *
 * | Confirmed base | [apply] result | Stream projection |
 * |---|---|---|
 * | non-null | equal value | pass through the confirmed envelope and its metadata |
 * | non-null | different non-null value | overlay data |
 * | non-null | `null` | absence (optimistic delete) |
 * | `null` | non-null value | overlay data (optimistic create) |
 * | `null` | `null` | confirmed absence |
 *
 * The engine invokes [apply] exactly once for each residence revision or matching [changes]
 * emission actually accepted by the key's single writer, independent of collector count. [apply]
 * must be pure, non-blocking, and no-throw, and must not call back into the Store. It runs outside
 * Store locks. [changes] is filtered by canonical key identity; it may complete normally, but it
 * must not fail. The mutations extension is the intended producer of change signals after its
 * confirmed-commit-then-retire ordering.
 *
 * A defensive violation by [apply] or [changes], including a self-originated cancellation while
 * the engine remains active, terminalizes projection for that key. Every current or future
 * projected stream then fails with a deterministic internal exception retaining the cause; the
 * engine never silently falls back to an unprojected value.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Overlay<K : StoreKey, V : Any> {
    /** Computes the current projected value for [key] from confirmed [base] or absence. */
    public fun apply(
        key: K,
        base: V?,
    ): V?

    /** Signals keys whose projection inputs changed without changing confirmed residence. */
    public val changes: Flow<StoreKey>
}
