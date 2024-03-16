package org.mobilenativefoundation.paging.core

/**
 * Represents a list of paging items.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @property data The list of [PagingData.Single] items representing the paging data.
 */
data class PagingItems<Id : Comparable<Id>, K : Any, P : Any, D : Any>(
    val data: List<PagingData.Single<Id, K, P, D>>
)
