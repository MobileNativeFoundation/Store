package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.Effect
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingState
import org.mobilenativefoundation.paging.core.impl.Constants.UNCHECKED_CAST
import kotlin.reflect.KClass

/**
 * A type alias representing a mapping from a [PagingAction] to a list of [Effect]s.
 */
typealias PagingActionToEffects<Id, K, P, D, E, A> = MutableMap<KClass<out PagingAction<Id, K, P, D, E, A>>, MutableList<Effect<Id, K, P, D, E, A, *, *>>>

/**
 * A type alias representing a mapping from a [PagingState] to a [PagingActionToEffects] map.
 */
typealias PagingStateToPagingActionToEffects<Id, K, P, D, E, A> = MutableMap<KClass<out PagingState<Id, K, P, D, E>>, PagingActionToEffects<Id, K, P, D, E, A>>

/**
 * A class for holding and managing effects based on [PagingAction] and [PagingState] types.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
@Suppress(UNCHECKED_CAST)
class EffectsHolder<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {
    private val effects: PagingStateToPagingActionToEffects<Id, K, P, D, E, A> = mutableMapOf()

    /**
     * Retrieves the list of effects associated with the specified [PagingAction] and [PagingState] types.
     *
     * @param PA The type of the [PagingAction].
     * @param S The type of the [PagingState].
     * @param action The [KClass] of the [PagingAction].
     * @param state The [KClass] of the [PagingState].
     * @return The list of effects associated with the specified [PagingAction] and [PagingState] types.
     */
    fun <PA : PagingAction<Id, K, P, D, E, A>, S : PagingState<Id, K, P, D, E>> get(
        action: KClass<out PagingAction<Id, K, P, D, E, A>>,
        state: KClass<out PagingState<Id, K, P, D, E>>
    ): List<Effect<Id, K, P, D, E, A, PA, S>> {
        action as KClass<PA>
        state as KClass<S>

        return effects[state]?.get(action) as? List<Effect<Id, K, P, D, E, A, PA, S>> ?: emptyList()
    }

    /**
     * Adds an effect to the list of effects associated with the specified [PagingAction] and [PagingState] types.
     *
     * @param PA The type of the [PagingAction].
     * @param S The type of the [PagingState].
     * @param action The [KClass] of the [PagingAction].
     * @param state The [KClass] of the [PagingState].
     * @param effect The effect to add.
     */
    fun <PA : PagingAction<Id, K, P, D, E, A>, S : PagingState<Id, K, P, D, E>> put(
        action: KClass<out PagingAction<*, *, *, *, *, *>>,
        state: KClass<out PagingState<*, *, *, *, *>>,
        effect: Effect<Id, K, P, D, E, A, PA, S>
    ) {
        action as KClass<out PA>
        state as KClass<out S>

        if (state !in effects) {
            effects[state] = mutableMapOf()
        }

        if (action !in effects[state]!!) {
            effects[state]!![action] = mutableListOf()
        }

        effects[state]!![action]!!.add(effect)
    }
}
