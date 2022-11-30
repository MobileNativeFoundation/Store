@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.impl

import com.dropbox.external.store5.Persister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe LRU cache implementation.
 */
class MemoryLruCache(private val maxSize: Int) : Persister<String> {
    internal var cache = mutableMapOf<String, Node<*>>()
    internal var head = headPointer
    internal var tail = tailPointer

    private val lock = Mutex()

    init {
        head.next = tail
        tail.prev = head
    }

    override fun <Output : Any> read(key: String): Flow<Output?> = flow {
        lock.withLock {
            if (cache.containsKey(key)) {
                val node = cache[key]!! as Node<Output>
                removeFromList(node)
                insertIntoHead(node)
                emit(node.value)
            } else {
                emit(null)
            }
        }
    }

    override suspend fun <Input : Any> write(key: String, input: Input): Boolean {
        lock.withLock {
            if (cache.containsKey(key)) {
                val node = cache[key]!! as Node<Input>
                removeFromList(node)
                insertIntoHead(node)
            } else {
                if (cache.size >= maxSize) {
                    removeFromTail()
                }
            }

            val node = Node(key, input)
            cache[key] = node
            insertIntoHead(node)
        }
        return true
    }

    override suspend fun delete(key: String): Boolean {
        lock.withLock {
            val node = cache[key]
            cache.remove(key)
            if (node != null) {
                removeFromList(node)
            }
        }
        return true
    }

    override suspend fun deleteAll(): Boolean {
        lock.withLock {
            cache.clear()

            head = headPointer
            tail = tailPointer
            head.next = tail
            tail.prev = head
        }
        return true
    }

    private fun <V : Any> insertIntoHead(node: Node<V>) {
        val nextHead = head.next!!
        head.next = node
        node.prev = head
        node.next = nextHead
        nextHead.prev = node
    }

    private fun <V : Any> removeFromList(node: Node<V>) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
    }

    private fun removeFromTail() {
        if (tail.prev == null) {
            return
        }

        val tail = tail.prev!!
        cache.remove(tail.key)
        removeFromList(tail)
    }

    internal data class Node<V : Any>(
        val key: String,
        var value: V,
        var next: Node<*>? = null,
        var prev: Node<*>? = null
    )

    companion object {
        private const val HEAD_KEY = "HEAD"
        private const val HEAD_VALUE = 0
        private const val TAIL_KEY = "TAIL"
        private const val TAIL_VALUE = -1

        internal val headPointer = Node(HEAD_KEY, HEAD_VALUE)
        internal val tailPointer = Node(TAIL_KEY, TAIL_VALUE)
    }
}
