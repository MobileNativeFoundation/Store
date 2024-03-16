package org.mobilenativefoundation.paging.core

/**
 * Defines the actions that can be dispatched to modify the paging state.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
sealed interface PagingAction<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any, out A : Any> {

    /**
     * Defines user-initiated actions.
     */
    sealed interface User<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any, out A : Any> : PagingAction<Id, K, P, D, E, A> {

        /**
         * Represents a user-initiated action to load data for a specific page key.
         *
         * @param key The page key to load data for.
         */
        data class Load<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any, out A : Any>(
            val key: PagingKey<K, P>,
        ) : User<Id, K, P, D, E, A>

        /**
         * Represents a custom user-initiated action.
         *
         * @param action The custom action payload.
         */
        data class Custom<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any, out A : Any>(
            val action: A
        ) : User<Id, K, P, D, E, A>
    }


    /**
     * Represents an app-initiated action to load data for a specific page key.
     *
     * @param key The page key to load data for.
     */
    data class Load<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
        val key: PagingKey<K, P>,
    ) : PagingAction<Id, K, P, D, E, A>

    /**
     * Represents an app-initiated action to update the paging state with loaded data.
     *
     * @param params The parameters associated with the loaded data.
     * @param data The loaded data.
     */
    data class UpdateData<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
        val params: PagingSource.LoadParams<K, P>,
        val data: PagingSource.LoadResult.Data<Id, K, P, D>
    ) : PagingAction<Id, K, P, D, E, A>

    /**
     * Represents an app-initiated action to update the paging state with an error.
     *
     * @param params The parameters associated with the error.
     * @param error The error that occurred.
     */
    data class UpdateError<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
        val params: PagingSource.LoadParams<K, P>,
        val error: PagingSource.LoadResult.Error<Id, K, P, D, E>
    ) : PagingAction<Id, K, P, D, E, A>

}