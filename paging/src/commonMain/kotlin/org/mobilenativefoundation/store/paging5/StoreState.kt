package org.mobilenativefoundation.store.paging5

/**
 * An interface that defines various states of data-fetching operations.
 */
sealed interface StoreState<out Id : Any, out Output : Identifiable<Id>> {

    /**
     * Represents the initial state.
     */
    data object Initial : StoreState<Nothing, Nothing>

    /**
     * Represents the loading state.
     */
    data object Loading : StoreState<Nothing, Nothing>


    /**
     * Represents successful fetch operations.
     */
    sealed interface Loaded<Id : Any, Output : Identifiable<Id>> : StoreState<Id, Output> {

        /**
         * Represents a successful fetch of an individual item.
         */
        data class Single<Id : Any, Output : Identifiable.Single<Id>>(val data: Output) : Loaded<Id, Output>

        /**
         * Represents a successful fetch of a collection of items.
         */
        data class Collection<Id : Any, SO : Identifiable.Single<Id>, CO : Identifiable.Collection<Id, SO>>(val data: CO) :
            Loaded<Id, CO>
    }

    /**
     * Represents unsuccessful fetch operations.
     */
    sealed interface Error : StoreState<Nothing, Nothing> {

        /**
         * Represents an unsuccessful fetch operation due to an exception.
         */
        data class Exception<CustomException : Any>(val error: CustomException) : Error

        /**
         * Represents an unsuccessful fetch operation due to an error with a message.
         */
        data class Message(val error: String) : Error
    }
}