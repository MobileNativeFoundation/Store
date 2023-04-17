package org.mobilenativefoundation.store.superstore5

sealed class SuperstoreResponseOrigin {
    object Cache : SuperstoreResponseOrigin()
    object SourceOfTruth : SuperstoreResponseOrigin()
    object Fetcher : SuperstoreResponseOrigin()
    data class Warehouse<T : Any>(val value: T) : SuperstoreResponseOrigin()
}
