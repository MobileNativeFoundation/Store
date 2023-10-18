package org.mobilenativefoundation.store.core5

interface KeyProvider<Id : Any, Single : StoreData.Single<Id>> {
    fun from(key: StoreKey.Collection<Id>, value: Single): StoreKey.Single<Id>
    fun from(key: StoreKey.Single<Id>, value: Single): StoreKey.Collection<Id>
}