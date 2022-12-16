package org.mobilenativefoundation.store.store5.data

internal interface Api<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    val data: MutableMap<Key, NetworkRepresentation>
    fun get(key: Key, fail: Boolean = false): NetworkRepresentation?
    fun post(key: Key, value: CommonRepresentation, fail: Boolean = false): NetworkWriteResponse
}
