package org.mobilenativefoundation.store.store5.data

internal interface Api<Key : Any, Output : Any> {
    val data: MutableMap<Key, Output>
    fun get(key: Key, fail: Boolean = false): Output?
    fun post(key: Key, value: Output, fail: Boolean = false): Output?
}
