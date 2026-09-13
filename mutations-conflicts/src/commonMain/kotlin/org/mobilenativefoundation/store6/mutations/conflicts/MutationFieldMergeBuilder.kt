@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationPresence

internal typealias MutationFieldMergeRegistration<V> =
    (
        base: MutationPresence<V>,
        mine: V,
        theirs: V,
        canvas: V,
        onBothChanged: MutationConflictBias,
    ) -> V

/**
 * This policy merges the fields you register and resolves every unregistered field to `theirs`.
 * The builder cannot enumerate a type's properties in common Kotlin, so it cannot detect an
 * unregistered locally-mutated field. Forgetting one silently surrenders that field's local edit.
 * Register every field the app mutates locally.
 */
@ExperimentalStoreApi
public class MutationFieldMergeBuilder<V : Any> internal constructor() {
    private val registrations = mutableListOf<MutationFieldMergeRegistration<V>>()
    private var isSealed: Boolean = false

    /** Registers one field; contested values resolve by the policy's onBothChanged bias. */
    @ExperimentalStoreApi
    public fun <F> field(
        get: (V) -> F,
        set: (V, F) -> V,
    ) {
        register(get = get, set = set, combine = null)
    }

    /** Registers one field with a caller-owned combiner for contested values. */
    @ExperimentalStoreApi
    public fun <F> field(
        get: (V) -> F,
        set: (V, F) -> V,
        combine: (base: V?, mine: F, theirs: F) -> F,
    ) {
        register(get = get, set = set, combine = combine)
    }

    internal fun sealAndSnapshot(): List<MutationFieldMergeRegistration<V>> {
        isSealed = true
        return registrations.toList()
    }

    private fun <F> register(
        get: (V) -> F,
        set: (V, F) -> V,
        combine: ((base: V?, mine: F, theirs: F) -> F)?,
    ) {
        check(!isSealed) { "MutationFieldMergeBuilder is sealed." }
        registrations += { base, mine, theirs, canvas, onBothChanged ->
            val mineField = get(mine)
            val theirsField = get(theirs)

            when (base) {
                is MutationPresence.Present -> {
                    val baseField = get(base.value)
                    when {
                        mineField == baseField -> canvas
                        theirsField == baseField -> set(canvas, mineField)
                        mineField == theirsField -> canvas
                        combine != null ->
                            set(
                                canvas,
                                combine(base.value, mineField, theirsField),
                            )

                        onBothChanged == MutationConflictBias.MINE -> set(canvas, mineField)
                        else -> canvas
                    }
                }

                MutationPresence.Absent ->
                    when {
                        mineField == theirsField -> canvas
                        combine != null -> set(canvas, combine(null, mineField, theirsField))
                        onBothChanged == MutationConflictBias.MINE -> set(canvas, mineField)
                        else -> canvas
                    }
            }
        }
    }
}
