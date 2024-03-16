package org.mobilenativefoundation.paging.core


/**
 * Represents paging data that can be either a single item or a collection of items.
 *
 * @param Id The type of the unique identifier for each item.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 */
sealed interface PagingData<out Id : Any, out K : Any, out P : Any, out D : Any> {

    /**
     * Represents a single item of paging data.
     *
     * @param Id The type of the unique identifier for the item.
     * @param K The type of the key used for paging.
     * @param P The type of the parameters associated with the item.
     * @param D The type of the data item.
     * @property id The unique identifier of the item.
     * @property data The data item.
     */
    data class Single<Id : Any, K : Any, P : Any, D : Any>(
        val id: Id,
        val data: D
    ) : PagingData<Id, K, P, D>

    /**
     * Represents a collection of paging data items.
     *
     * @param Id The type of the unique identifier for each item.
     * @param K The type of the key used for paging.
     * @param P The type of the parameters associated with each page of data.
     * @param D The type of the data items.
     * @property items The list of paging data items in the collection.
     * @property itemsBefore The number of items before the current collection, if known.
     * @property itemsAfter The number of items after the current collection, if known.
     * @property prevKey The paging key of the previous page of data.
     * @property nextKey The paging key of the next page of data, if available.
     */
    data class Collection<Id : Any, K : Any, P : Any, D : Any>(
        val items: List<Single<Id, K, P, D>>,
        val itemsBefore: Int?,
        val itemsAfter: Int?,
        val prevKey: PagingKey<K, P>,
        val nextKey: PagingKey<K, P>?,
    ) : PagingData<Id, K, P, D>
}