package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * One [org.mobilenativefoundation.store6.core.Store] call recorded by a [FakeStore].
 *
 * Each variant names the operation that produced it and carries that operation's arguments;
 * [Freshness] values are recorded verbatim and never interpreted. `FakeStore.interactions` returns
 * them in call order and `FakeStore.clearInteractions` empties the list. A stream records its
 * [Stream] entry when collection starts, not when the flow is built, and [Close] is recorded at
 * most once because closing an already-closed fake has no effect.
 */
@ExperimentalStoreApi
public sealed class FakeStoreInteraction {
    public class Stream(
        public val key: StoreKey,
        public val freshness: Freshness,
    ) : FakeStoreInteraction()

    public class Get(
        public val key: StoreKey,
        public val freshness: Freshness,
    ) : FakeStoreInteraction()

    public class Invalidate(public val key: StoreKey) : FakeStoreInteraction()

    public class InvalidateNamespace(
        public val namespace: StoreNamespace,
    ) : FakeStoreInteraction()

    public data object InvalidateAll : FakeStoreInteraction()

    public class Clear(public val key: StoreKey) : FakeStoreInteraction()

    public class ClearNamespace(
        public val namespace: StoreNamespace,
    ) : FakeStoreInteraction()

    public data object ClearAll : FakeStoreInteraction()

    public data object Close : FakeStoreInteraction()
}
