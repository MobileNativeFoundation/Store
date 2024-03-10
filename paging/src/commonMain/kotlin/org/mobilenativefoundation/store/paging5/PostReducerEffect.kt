package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface PostReducerEffect<
    Id : Comparable<Id>,
    CK : StoreKey.Collection<Id>,
    SO :
    StoreData.Single<Id>,
    CE : Any,
    S : PagingState<Id, CK, SO, CE>,
    A : PagingAction,
    > {
    fun run(
        state: S,
        action: A,
        dispatch: (PagingAction) -> Unit,
    )
}
