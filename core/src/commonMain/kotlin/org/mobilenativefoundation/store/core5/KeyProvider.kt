package org.mobilenativefoundation.store.core5

@ExperimentalStoreApi
interface KeyProvider<Id : Any, Single : StoreData.Single<Id>> {
    fun fromCollection(
        key: StoreKey.Collection<Id>,
        value: Single,
    ): StoreKey.Single<Id>

    fun fromSingle(
        key: StoreKey.Single<Id>,
        value: Single,
    ): StoreKey.Collection<Id>
}
