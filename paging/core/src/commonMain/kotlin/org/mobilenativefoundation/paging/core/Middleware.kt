package org.mobilenativefoundation.paging.core

/**
 * Represents a middleware that intercepts and modifies paging actions before they reach the reducer.
 *
 * [Middleware] allows for pre-processing, logging, or any other custom logic to be applied to actions before they are handled by the [Reducer].
 * It can also modify or replace the action before passing it to the next [Middleware] or [Reducer].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
interface Middleware<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {

    /**
     * Applies the middleware logic to the given [action].
     *
     * The middleware can perform any necessary pre-processing, logging, or modification of the action
     * before invoking the [next] function to pass the action to the next middleware or the reducer.
     *
     * @param action The paging action to be processed by the middleware.
     * @param next A suspending function that should be invoked with the processed action to pass it to the next middleware or the reducer.
     * If the middleware does not want to pass the action further, it can choose not to invoke this function.
     */
    suspend fun apply(action: PagingAction<Id, K, P, D, E, A>, next: suspend (PagingAction<Id, K, P, D, E, A>) -> Unit)
}