package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
sealed class PagingAction {
    sealed class User : PagingAction() {
        data class Load<Id : Comparable<Id>, CK : StoreKey.Collection<Id>>(
            val key: CK,
        ) : User()

        object Refresh : User()

        data class Custom<CA : Any>(
            val action: CA,
        ) : User()
    }

    sealed class App : PagingAction() {
        object Start : App()

        data class Load<Id : Comparable<Id>, CK : StoreKey.Collection<Id>>(
            val key: CK,
        ) : App()

        data class UpdateData<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
            val params: PagingSource.LoadParams<Id, CK>,
            val page: PagingSource.LoadResult.Page<Id, CK, SO>,
        ) : App()

        sealed class UpdateError<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, out CE : Any> : App() {
            abstract val params: PagingSource.LoadParams<Id, CK>

            data class Exception<Id : Comparable<Id>, CK : StoreKey.Collection<Id>>(
                val error: Throwable,
                override val params: PagingSource.LoadParams<Id, CK>,
            ) : UpdateError<Id, CK, Nothing>()

            data class Message<Id : Comparable<Id>, CK : StoreKey.Collection<Id>>(
                val error: String,
                override val params: PagingSource.LoadParams<Id, CK>,
            ) : UpdateError<Id, CK, Nothing>()

            data class Custom<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, CE : Any>(
                val error: CE,
                override val params: PagingSource.LoadParams<Id, CK>,
            ) : UpdateError<Id, CK, CE>()
        }
    }
}
