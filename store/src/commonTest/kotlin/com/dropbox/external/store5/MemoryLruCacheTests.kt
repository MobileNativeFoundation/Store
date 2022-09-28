@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("UNCHECKED_CAST")

package com.dropbox.external.store5

import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryLruCacheTests {
    private val testScope = TestScope()
    private lateinit var memoryLruCache: MemoryLruCache

    @BeforeTest
    fun before() {
        memoryLruCache = MemoryLruCache(10)
    }

    private fun headPointer() = memoryLruCache.head
    private fun tailPointer() = memoryLruCache.tail
    private fun head() = memoryLruCache.head.next as MemoryLruCache.Node<Note>
    private fun tail() = memoryLruCache.tail.prev as MemoryLruCache.Node<Note>

    @Test
    fun writeAndRead() {
        testScope.launch {
            memoryLruCache.write(FakeNotes.One.key, FakeNotes.One.note)
        }
        testScope.advanceUntilIdle()


        var headPointer = headPointer()
        var tailPointer = tailPointer()
        var head = head()
        var tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.One.note, head.value)
        assertEquals(FakeNotes.One.note, tail.value)
        assertEquals(1, memoryLruCache.cache.size)

        testScope.launch {
            memoryLruCache.write(FakeNotes.Two.key, FakeNotes.Two.note)
        }
        testScope.advanceUntilIdle()



        headPointer = headPointer()
        tailPointer = tailPointer()
        head = head()
        tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
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

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.One.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(2, memoryLruCache.cache.size)
    }

    @Test
    fun write10ShouldNotRemoveAny() {

        testScope.launch {
            FakeNotes.listN(10).forEach {
                memoryLruCache.write(it.key, it.note)
            }
        }

        testScope.advanceUntilIdle()

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Ten.note, head.value)
        assertEquals(FakeNotes.One.note, tail.value)
        assertEquals(10, memoryLruCache.cache.size)
    }


    @Test
    fun write11ShouldRemoveFirst() {

        testScope.launch {
            FakeNotes.listN(11).forEach {
                memoryLruCache.write(it.key, it.note)
            }
        }

        testScope.advanceUntilIdle()

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Eleven.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(10, memoryLruCache.cache.size)
    }

    @Test
    fun delete() {

        testScope.launch {
            FakeNotes.listN(10).forEach {
                memoryLruCache.write(it.key, it.note)
            }

            memoryLruCache.delete(FakeNotes.Ten.key)
            memoryLruCache.delete(FakeNotes.Nine.key)
            memoryLruCache.delete(FakeNotes.One.key)
        }

        testScope.advanceUntilIdle()


        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)
        assertEquals(FakeNotes.Eight.note, head.value)
        assertEquals(FakeNotes.Two.note, tail.value)
        assertEquals(7, memoryLruCache.cache.size)

        assertEquals(null, memoryLruCache.cache[FakeNotes.Ten.key])
        assertEquals(null, memoryLruCache.cache[FakeNotes.Nine.key])
        assertEquals(null, memoryLruCache.cache[FakeNotes.One.key])
    }

    @Test
    fun clear() {

        testScope.launch {
            FakeNotes.list().forEach {
                memoryLruCache.write(it.key, it.note)
            }

            memoryLruCache.deleteAll()
        }

        testScope.advanceUntilIdle()

        val headPointer = headPointer()
        val tailPointer = tailPointer()
        val head = head()
        val tail = tail()

        assertEquals(MemoryLruCache.headPointer, headPointer)
        assertEquals(MemoryLruCache.tailPointer, tailPointer)

        assertEquals<Any>(MemoryLruCache.headPointer.value, tail.value)
        assertEquals<Any>(MemoryLruCache.tailPointer.value, head.value)
        assertEquals(0, memoryLruCache.cache.size)
    }
}
