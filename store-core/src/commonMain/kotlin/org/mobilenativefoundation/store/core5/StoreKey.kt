package org.mobilenativefoundation.store.core5

/**
 * An interface that defines keys used by Store for data-fetching operations.
 * Allows Store to fetch individual items and collections of items.
 * Provides mechanisms for ID-based fetch, page-based fetch, and cursor-based fetch.
 * Includes options for sorting and filtering.
 */
@ExperimentalStoreApi
interface StoreKey<out Id : Any> {
    /**
     * Represents a key for fetching an individual item.
     */
    interface Single<Id : Any> : StoreKey<Id> {
        val id: Id
    }

    /**
     * Represents a key for fetching collections of items.
     */
    interface Collection<out Id : Any> : StoreKey<Id> {
        val insertionStrategy: InsertionStrategy

        /**
         * Represents a key for page-based fetching.
         */
        interface Page : Collection<Nothing> {
            val page: Int
            val size: Int
            val sort: Sort?
            val filters: List<Filter<*>>?
        }

        /**
         * Represents a key for cursor-based fetching.
         */
        interface Cursor<out Id : Any> : Collection<Id> {
            val cursor: Id?
            val size: Int
            val sort: Sort?
            val filters: List<Filter<*>>?
        }
    }

    /**
     * An enum defining sorting options that can be applied during fetching.
     */
    enum class Sort {
        NEWEST,
        OLDEST,
        ALPHABETICAL,
        REVERSE_ALPHABETICAL,
    }

    /**
     * Defines filters that can be applied during fetching.
     */
    interface Filter<Value : Any> {
        operator fun invoke(items: List<Value>): List<Value>
    }
}
