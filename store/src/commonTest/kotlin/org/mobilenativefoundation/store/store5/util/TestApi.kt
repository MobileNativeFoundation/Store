package org.mobilenativefoundation.store.store5.util

internal interface TestApi<Key : Any, Network : Any, Common : Any, Response : Any> {
    fun get(key: Key, fail: Boolean = false, ttl: Long? = null): Network
    fun post(key: Key, value: Common, fail: Boolean = false): Response
}
