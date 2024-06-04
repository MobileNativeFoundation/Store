package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingState
import org.mobilenativefoundation.paging.core.impl.Constants.UNCHECKED_CAST
import kotlin.reflect.KClass

/**
 * A class for launching effects based on dispatched [PagingAction]s and the current [PagingState].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 * @property effectsHolder The [EffectsHolder] instance holding the effects.
 */
@Suppress(UNCHECKED_CAST)
class EffectsLauncher<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    private val effectsHolder: EffectsHolder<Id, K, P, D, E, A>
) {

    fun <PA : PagingAction<Id, K, P, D, E, A>, S : PagingState<Id, K, P, D, E>> launch(action: PA, state: S, dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit) {

        effectsHolder.get<PA, S>(action::class, state::class).forEach { effect ->
            effect(action, state, dispatch)
        }

        effectsHolder.get<PA, PagingState<Id, K, P, D, E>>(action::class, PagingState::class as KClass<out PagingState<Id, K, P, D, E>>).forEach { effect ->
            effect(action, state, dispatch)
        }
    }
}