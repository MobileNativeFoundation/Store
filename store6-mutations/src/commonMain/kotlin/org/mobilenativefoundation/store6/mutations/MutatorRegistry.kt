@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

internal class MutatorRegistryOwnership

/**
 * A typed reference to a registered mutation projection.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public class MutatorRef<K : StoreKey, V : Any, A : Any> internal constructor(
    /** The stable identifier persisted with pending intents. */
    @ExperimentalStoreApi
    public val id: String,
    internal val ownership: MutatorRegistryOwnership,
)

/**
 * The mutation projections available to a mutation engine.
 *
 * PROVISIONAL pending Issue 021: registry construction and ownership may change before that issue
 * is complete.
 */
@ExperimentalStoreApi
public class MutatorRegistry<K : StoreKey, V : Any> internal constructor(
    internal val ownership: MutatorRegistryOwnership,
    internal val projections: Map<String, (V?, Any) -> V?>,
)

/**
 * Registers typed mutation projections.
 *
 * PROVISIONAL pending Issue 021: this builder may change before that issue is complete.
 */
@ExperimentalStoreApi
public class MutatorRegistryBuilder<K : StoreKey, V : Any> internal constructor() {
    private val ownership = MutatorRegistryOwnership()
    private val projections = mutableMapOf<String, (V?, Any) -> V?>()
    private var isBuilt = false

    /**
     * Registers [project] under [id] and returns the typed reference used to enqueue its arguments.
     *
     * [project] runs synchronously inside Store's Overlay application and may be invoked repeatedly
     * or concurrently for different keys. It must be a pure, deterministic, non-blocking function
     * of `(base, args)`, and it must not call back into Store. A thrown failure is contained and
     * reported through `MutationStore.poisoned`.
     *
     * PROVISIONAL pending Issue 021: registration semantics may change before that issue is
     * complete.
     */
    @ExperimentalStoreApi
    public fun <A : Any> mutator(
        id: String,
        project: (V?, A) -> V?,
    ): MutatorRef<K, V, A> {
        require(!isBuilt) {
            "MutatorRegistryBuilder is already built."
        }
        require(id !in projections) {
            "Mutator id '$id' is already registered."
        }
        @Suppress("UNCHECKED_CAST")
        val erased = project as (V?, Any) -> V?
        projections[id] = erased
        return MutatorRef(id, ownership)
    }

    internal fun build(): MutatorRegistry<K, V> {
        require(!isBuilt) {
            "MutatorRegistryBuilder is already built."
        }
        isBuilt = true
        return MutatorRegistry(
            ownership = ownership,
            projections = projections.toMap(),
        )
    }
}

/**
 * Builds a registry of typed mutation projections.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> mutatorRegistry(
    configure: MutatorRegistryBuilder<K, V>.() -> Unit,
): MutatorRegistry<K, V> = MutatorRegistryBuilder<K, V>().apply(configure).build()
