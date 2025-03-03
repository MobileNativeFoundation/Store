package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.cache5.Cache

@Suppress("UNCHECKED_CAST")
class TestCache<Key : Any, Value : Any> : Cache<Key, Value> {
  private val map = HashMap<Key, Value>()
  var getIfPresentCalls = 0
  var getOrPutCalls = 0
  var getAllPresentCalls = 0
  var putCalls = 0
  var putAllCalls = 0
  var invalidateCalls = 0
  var invalidateAllKeysCalls = 0
  var invalidateAllCalls = 0
  var sizeCalls = 0

  override fun getIfPresent(key: Key): Value? {
    getIfPresentCalls++
    return map[key]
  }

  override fun getOrPut(key: Key, valueProducer: () -> Value): Value {
    getOrPutCalls++
    return map.getOrPut(key, valueProducer)
  }

  override fun getAllPresent(keys: List<*>): Map<Key, Value> {
    getAllPresentCalls++
    return keys.mapNotNull { it as? Key }.associateWithNotNull { key -> map[key] }
  }

  override fun put(key: Key, value: Value) {
    putCalls++
    map[key] = value
  }

  override fun putAll(map: Map<Key, Value>) {
    putAllCalls++
    map.forEach { (k, v) -> put(k, v) }
  }

  override fun invalidate(key: Key) {
    invalidateCalls++
    map.remove(key)
  }

  override fun invalidateAll(keys: List<Key>) {
    invalidateAllKeysCalls++
    keys.forEach { map.remove(it) }
  }

  override fun invalidateAll() {
    invalidateAllCalls++
    map.clear()
  }

  override fun size(): Long {
    sizeCalls++
    return map.size.toLong()
  }

  private inline fun <K, V> Iterable<K>.associateWithNotNull(transform: (K) -> V?): Map<K, V> {
    val destination = mutableMapOf<K, V>()
    for (element in this) {
      transform(element)?.let { destination[element] = it }
    }
    return destination
  }
}
