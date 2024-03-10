package org.mobilenativefoundation.store.core5

/**
 * An interface that defines items that can be uniquely identified.
 * Every item that implements the [StoreData] interface must have a means of identification.
 * This is useful in scenarios when data can be represented as singles or collections.
 */
@ExperimentalStoreApi
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
    interface Collection<Id : Any, CK : StoreKey.Collection<Id>, SO : Single<Id>> : StoreData<Id> {
        val items: List<SO>
        val itemsBefore: Int?
        val itemsAfter: Int?
        val prevKey: CK
        val nextKey: CK?

        /**
         * Returns a new collection with the updated items.
         */
        fun copyWith(items: List<SO>): Collection<Id, *, SO>

        /**
         * Inserts items to the existing collection and returns the updated collection.
         */
        fun insertItems(
            strategy: InsertionStrategy,
            items: List<SO>,
        ): Collection<Id, *, SO>
    }
}
