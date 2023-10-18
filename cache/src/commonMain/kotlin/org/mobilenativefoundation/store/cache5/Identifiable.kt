package org.mobilenativefoundation.store.cache5

@Deprecated("Use StoreMultiCache instead of MultiCache")
interface Identifiable<Id : Any> {
    val id: Id
}
