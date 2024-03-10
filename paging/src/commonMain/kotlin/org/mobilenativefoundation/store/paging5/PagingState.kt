package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
sealed class PagingState<Id : Comparable<Id>, out CK : StoreKey.Collection<Id>, out SO : StoreData.Single<Id>, out CE : Any> {
    abstract val currentKey: CK
    abstract val prefetchPosition: Id?

    data class Initial<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
        override val currentKey: CK,
        override val prefetchPosition: Id?,
    ) : PagingState<Id, CK, SO, Nothing>()

    data class LoadingInitial<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
        override val currentKey: CK,
        override val prefetchPosition: Id?,
    ) : PagingState<Id, CK, SO, Nothing>()

    sealed class Data<Id : Comparable<Id>, out CK : StoreKey.Collection<Id>, out SO : StoreData.Single<Id>> :
        PagingState<Id, CK, SO, Nothing>() {
        abstract val data: List<SO>
        abstract val itemsBefore: Int?
        abstract val itemsAfter: Int?
        abstract val nextKey: CK?

        data class Idle<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
            override val data: List<SO>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: CK?,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Data<Id, CK, SO>()

        data class LoadingMore<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
            override val data: List<SO>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: CK?,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Data<Id, CK, SO>()

        data class Refreshing<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
            override val data: List<SO>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: CK?,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Data<Id, CK, SO>()

        data class ErrorLoadingMore<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
            val error: Error<Id, CK, SO, CE>,
            override val data: List<SO>,
            override val itemsBefore: Int?,
            override val itemsAfter: Int?,
            override val nextKey: CK?,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Data<Id, CK, SO>()
    }

    sealed class Error<Id : Comparable<Id>, out CK : StoreKey.Collection<Id>, out SO : StoreData.Single<Id>, out CE : Any> :
        PagingState<Id, CK, SO, CE>() {
        data class Exception<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
            val error: Throwable,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Error<Id, CK, SO, CE>()

        data class Message<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
            val error: String,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Error<Id, CK, SO, CE>()

        data class Custom<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
            val error: CE,
            override val currentKey: CK,
            override val prefetchPosition: Id?,
        ) : Error<Id, CK, SO, CE>()
    }
}
