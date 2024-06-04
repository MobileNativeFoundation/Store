package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mobilenativefoundation.paging.core.Middleware
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingState
import org.mobilenativefoundation.paging.core.Reducer

/**
 * A real implementation of the [Dispatcher] interface for handling paging actions and managing the paging state.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 * @property stateManager The [StateManager] instance for managing the paging state.
 * @property middleware The list of [Middleware] instances to be applied to the dispatched actions.
 * @property reducer The [Reducer] instance for reducing the paging state based on the dispatched actions.
 * @property effectsLauncher The [EffectsLauncher] instance for launching effects based on the dispatched actions and the current state.
 * @property childScope The [CoroutineScope] in which the dispatcher will operate.
 */
class RealDispatcher<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    private val stateManager: StateManager<Id, K, P, D, E>,
    private val middleware: List<Middleware<Id, K, P, D, E, A>>,
    private val reducer: Reducer<Id, K, P, D, E, A>,
    private val effectsLauncher: EffectsLauncher<Id, K, P, D, E, A>,
    private val childScope: CoroutineScope,
) : Dispatcher<Id, K, P, D, E, A> {

    /**
     * Dispatches a paging action to the middleware and reducer chain.
     *
     * @param PA The type of the paging action being dispatched.
     * @param action The paging action to dispatch.
     * @param index The index of the middleware to start dispatching from.
     */
    override fun <PA : PagingAction<Id, K, P, D, E, A>> dispatch(action: PA, index: Int) {
        if (index < middleware.size) {

            childScope.launch {
                middleware[index].apply(action) { nextAction ->
                    dispatch(nextAction, index + 1)
                }
            }

        } else {
            childScope.launch {
                reduceAndLaunchEffects(action)
            }
        }
    }

    /**
     * Reduces the paging state based on the dispatched action and launches the corresponding effects.
     *
     * @param PA The type of the paging action being dispatched.
     * @param action The paging action to reduce and launch effects for.
     */
    private suspend fun <PA : PagingAction<Id, K, P, D, E, A>> reduceAndLaunchEffects(action: PA) {
        val prevState = stateManager.state.value
        val nextState = reducer.reduce(action, prevState)

        stateManager.update(nextState)

        when (nextState) {
            is PagingState.Initial -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Data.Idle -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Data.ErrorLoadingMore<Id, K, P, D, E, *> -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Data.LoadingMore -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Error.Custom -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Error.Exception -> effectsLauncher.launch(action, nextState, ::dispatch)
            is PagingState.Loading -> effectsLauncher.launch(action, nextState, ::dispatch)
        }
    }
}