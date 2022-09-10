@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5.impl

import com.dropbox.external.store5.Persister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

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
        lock.lock()

        if (cache.containsKey(key)) {
            val node = cache[key]!! as Node<Output>
            removeFromList(node)
            insertIntoHead(node)
            emit(node.value)
        } else {
            emit(null)
        }

        lock.unlock()
    }

    override suspend fun <Input : Any> write(key: String, input: Input): Boolean {
        lock.lock()

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

        lock.unlock()

        return true
    }

    override suspend fun delete(key: String): Boolean {
        lock.lock()

        val node = cache[key]
        cache.remove(key)
        if (node != null) {
            removeFromList(node)
        }

        lock.unlock()
        return true
    }

    override suspend fun delete(): Boolean {
        lock.lock()

        cache.clear()

        head = headPointer
        tail = tailPointer
        head.next = tail
        tail.prev = head

        lock.unlock()
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