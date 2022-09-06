@file:Suppress("UNCHECKED_CAST")

package com.dropbox.store.impl

import co.touchlab.stately.collections.IsoMutableMap
import co.touchlab.stately.isolate.IsolateState
import com.dropbox.store.Storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ShareableLruCache(private val maxSize: Int) : Storage<String> {
    internal var cache = shareableMutableMapOf<String, Node<*>>()
    internal var head = shareableNodeOf(headPointer)
    internal var tail = shareableNodeOf(tailPointer)

    init {
        head.access { head -> head.next = tail.access { tail -> tail } }
        tail.access { tail -> tail.prev = head.access { head -> head } }
    }

    override fun <Output : Any> read(key: String): Flow<Output?> = flow {
        if (cache.containsKey(key)) {
            val node = cache[key]!! as Node<Output>
            removeFromList(node)
            insertIntoHead(node)
            emit(node.value)
        } else {
            emit(null)
        }
    }

    override fun <Input : Any> write(key: String, input: Input): Boolean {
        if (cache.containsKey(key)) {
            val node = cache[key]!! as Node<Input>
            removeFromList(node)
            insertIntoHead(node)
            node.value = input
        } else {
            if (cache.size >= maxSize) {
                removeFromTail()
            }

            val node = Node(key, input)
            cache[key] = node
            insertIntoHead(node)
        }

        return true
    }

    override fun delete(key: String): Boolean {
        val node = cache[key]
        cache.remove(key)
        if (node != null) {
            removeFromList(node)
        }

        return true
    }

    override fun clear(): Boolean {
        cache.clear()

        cache.dispose()
        tail.dispose()
        head.dispose()

        cache = shareableMutableMapOf()
        head = shareableNodeOf(headPointer)
        tail = shareableNodeOf(tailPointer)

        head.access { head -> head.next = tail.access { tail -> tail } }
        tail.access { tail -> tail.prev = head.access { head -> head } }

        return true
    }

    private fun <V : Any> insertIntoHead(node: Node<V>) {
        val nextHead = head.access { it.next }!!
        head.access { it.next = node }
        node.prev = head.access { it }
        node.next = nextHead
        nextHead.prev = node
    }

    private fun <V : Any> removeFromList(node: Node<V>) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
    }

    private fun removeFromTail() {
        if (tail.access { it.prev == null }) {
            return
        }
        val tail = tail.access { it.prev }!!

        cache.remove(tail.key)
        removeFromList(tail)
    }

    internal data class Node<V : Any>(
        val key: String,
        var value: V,
        var next: Node<*>? = null,
        var prev: Node<*>? = null
    )

    private inline fun <V : Any> shareableNodeOf(node: Node<V>) = IsolateState { node }
    private inline fun <K : Any, V : Any> shareableMutableMapOf() = IsoMutableMap<K, V>()

    companion object {
        private const val HEAD_KEY = "HEAD"
        private const val HEAD_VALUE = 0
        private const val TAIL_KEY = "TAIL"
        private const val TAIL_VALUE = -1

        internal val headPointer = Node(HEAD_KEY, HEAD_VALUE)
        internal val tailPointer = Node(TAIL_KEY, TAIL_VALUE)
    }
}