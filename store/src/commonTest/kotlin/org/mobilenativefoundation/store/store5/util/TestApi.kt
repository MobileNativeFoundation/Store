package org.mobilenativefoundation.store.store5.util

internal interface TestApi<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    fun get(key: Key, fail: Boolean = false): NetworkRepresentation?
    fun post(key: Key, value: CommonRepresentation, fail: Boolean = false): NetworkWriteResponse
}
