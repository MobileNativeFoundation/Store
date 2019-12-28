package com.dropbox.android.external.store4

/**
 * Persisters should implement Clearable if they want store.clear(key) to also clear the persister
 *
 * @param <T> Type of key/request param in store
</T> */
@Deprecated("") //used in tests
interface Clearable<T> {
    fun clear(key: T)
}
