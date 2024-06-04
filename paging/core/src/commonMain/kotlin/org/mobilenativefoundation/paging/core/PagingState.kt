package org.mobilenativefoundation.paging.core

/**
 * Represents the current state of the paging data.
 *
 * The paging state can be in different stages, such as [Initial], [Loading], [Error], or [Data].
 * It can contain the current key, prefetch position, errors, and data, such as loaded items and the next key.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 */
sealed interface PagingState<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any> {
    val currentKey: PagingKey<K, P>
    val prefetchPosition: PagingKey<K, P>?

    data class Initial<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
        override val currentKey: PagingKey<K, P>,
        override val prefetchPosition: PagingKey<K, P>?
    ) : PagingState<Id, K, P, D, E>

    data class Loading<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
        override val currentKey: PagingKey<K, P>,
        override val prefetchPosition: PagingKey<K, P>?
    ) : PagingState<Id, K, P, D, E>

    sealed interface Error<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any, out RE : Any> : PagingState<Id, K, P, D, E> {
        val error: RE

        data class Exception<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
            override val error: Throwable,
            override val currentKey: PagingKey<K, P>,
            override val prefetchPosition: PagingKey<K, P>?
        ) : Error<Id, K, P, D, E, Throwable>

        data class Custom<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
            override val error: E,
            override val currentKey: PagingKey<K, P>,
            override val prefetchPosition: PagingKey<K, P>?
        ) : Error<Id, K, P, D, E, E>
    }

    sealed interface Data<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> : PagingState<Id, K, P, D, E> {
        val data: List<PagingData.Single<Id, K, P, D>>
        val itemsBefore: Int?
        val itemsAfter: Int?
        val nextKey: PagingKey<K, P>?

        data class Idle<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
            override val data: List<PagingData.Single<Id, K, P, D>>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: PagingKey<K, P>?,
            override val currentKey: PagingKey<K, P>,
            override val prefetchPosition: PagingKey<K, P>?
        ) : Data<Id, K, P, D, E>

        data class LoadingMore<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
            override val data: List<PagingData.Single<Id, K, P, D>>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: PagingKey<K, P>?,
            override val currentKey: PagingKey<K, P>,
            override val prefetchPosition: PagingKey<K, P>?
        ) : Data<Id, K, P, D, E>

        data class ErrorLoadingMore<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, RE : Any>(
            override val error: RE,
            override val data: List<PagingData.Single<Id, K, P, D>>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: PagingKey<K, P>?,
            override val currentKey: PagingKey<K, P>,
            override val prefetchPosition: PagingKey<K, P>?
        ) : Data<Id, K, P, D, E>, Error<Id, K, P, D, E, RE>
    }
}