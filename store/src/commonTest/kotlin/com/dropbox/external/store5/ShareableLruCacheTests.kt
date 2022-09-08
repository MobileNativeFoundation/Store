@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5

import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.ShareableLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals


class ShareableLruCacheTests {
    private val testScope = TestScope()
    private lateinit var memoryLruCache: ShareableLruCache

    @BeforeTest
    fun before() {
        memoryLruCache = ShareableLruCache(10)
    }

    private fun headPointer() = memoryLruCache.head.access { it }
    private fun tailPointer() = memoryLruCache.tail.access { it }
    private fun head() = memoryLruCache.head.access { it.next } as ShareableLruCache.Node<Note>
    private fun tail() = memoryLruCache.tail.access { it.prev } as ShareableLruCache.Node<Note>

    @Test
    fun writeAndRead() {
        memoryLruCache.write(FakeNotes.One.key, FakeNotes.One.note)
        var headPointer = headPointer()
        var tailPointer = tailPointer()
        var head = head()
        var tail = tail()


        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.One.note, head.value)
        assertEquals(FakeNotes.One.note, tail.value)
        assertEquals(1, memoryLruCache.cache.size)

        memoryLruCache.write(FakeNotes.Two.key, FakeNotes.Two.note)
        headPointer = headPointer()
        tailPointer = tailPointer()
        head = head()
        tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Two.note, head.value)
        assertEquals(FakeNotes.One.note, tail.value)
        assertEquals(2, memoryLruCache.cache.size)

        val result = memoryLruCache.read<Note>(FakeNotes.One.key)

        testScope.runTest {
            assertEquals(FakeNotes.One.note, result.last())
        }

        headPointer = headPointer()
        tailPointer = tailPointer()
        head = head()
        tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.One.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(2, memoryLruCache.cache.size)
    }

    @Test
    fun write10() {
        FakeNotes.listN(10).forEach {
            memoryLruCache.write(it.key, it.note)
        }

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Ten.note, head.value)
        assertEquals(FakeNotes.One.note, tail.value)
        assertEquals(10, memoryLruCache.cache.size)
    }


    @Test
    fun write11() {
        FakeNotes.listN(11).forEach {
            memoryLruCache.write(it.key, it.note)
        }

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Eleven.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(10, memoryLruCache.cache.size)
    }

    @Test
    fun delete() {
        FakeNotes.listN(10).forEach {
            memoryLruCache.write(it.key, it.note)
        }

        memoryLruCache.delete(FakeNotes.Ten.key)
        memoryLruCache.delete(FakeNotes.Nine.key)
        memoryLruCache.delete(FakeNotes.One.key)

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Eight.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(7, memoryLruCache.cache.size)

        assertEquals(null, memoryLruCache.cache[FakeNotes.Ten.key])
        assertEquals(null, memoryLruCache.cache[FakeNotes.Nine.key])
        assertEquals(null, memoryLruCache.cache[FakeNotes.One.key])
    }

    @Test
    fun clear() {
        FakeNotes.list().forEach {
            memoryLruCache.write(it.key, it.note)
        }

        memoryLruCache.clear()

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(ShareableLruCache.headPointer, headPointer)
        assertEquals(ShareableLruCache.tailPointer, tailPointer)

        assertEquals<Any>(ShareableLruCache.headPointer.value, tail.value)
        assertEquals<Any>(ShareableLruCache.tailPointer.value, head.value)
        assertEquals(0, memoryLruCache.cache.size)
    }
}
