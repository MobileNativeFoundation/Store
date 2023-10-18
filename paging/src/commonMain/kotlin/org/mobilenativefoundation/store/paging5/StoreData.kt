package org.mobilenativefoundation.store.paging5


/**
 * An interface that defines items that can be uniquely identified.
 * Every item that implements the [StoreData] interface must have a means of identification.
 * This is useful in scenarios when data can be represented as singles or collections.
 */

interface StoreData<out Id : Any> {

    /**
     * Represents a single identifiable item.
     */
    interface Single<Id : Any> : StoreData<Id> {
        val id: Id
    }

    /**
     * Represents a collection of identifiable items.
     */
    interface Collection<Id : Any, S : Single<Id>> : StoreData<Id> {
        val items: List<S>

        /**
         * Returns a new collection with the updated items.
         */
        fun copyWith(items: List<S>): Collection<Id, S>

        /**
         * Inserts items to the existing collection and returns the updated collection.
         */
        fun insertItems(strategy: InsertionStrategy, items: List<S>): Collection<Id, S>
    }
}