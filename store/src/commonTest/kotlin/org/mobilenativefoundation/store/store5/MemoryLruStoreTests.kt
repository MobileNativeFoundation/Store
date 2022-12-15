@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.data.fake.FakeNotes
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.impl.MemoryLruStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
//
//class MemoryLruStoreTests {
//    private val testScope = TestScope()
//    private lateinit var memoryLruStore: MemoryLruStore<String, Note>
//
//    @BeforeTest
//    fun before() {
//        memoryLruStore = MemoryLruStore<String, Note>(10)
//    }
//
//    private fun headPointer() = memoryLruStore..
//    private fun tailPointer() = memoryLruStore.tail
//    private fun head() = memoryLruStore.head.next as MemoryLruStore.Node<Note>
//    private fun tail() = memoryLruStore.tail.prev as MemoryLruStore.Node<Note>
//
//    @Test
//    fun writeAndRead() = testScope.runTest {
//        memoryLruStore.write(FakeNotes.One.key, FakeNotes.One.note)
//        testScope.advanceUntilIdle()
//
//        var headPointer = headPointer()
//        var tailPointer = tailPointer()
//        var head = head()
//        var tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.One.note, head.value)
//        assertEquals(FakeNotes.One.note, tail.value)
//        assertEquals(1, memoryLruStore.cache.size)
//
//        memoryLruStore.write(FakeNotes.Two.key, FakeNotes.Two.note)
//        testScope.advanceUntilIdle()
//
//        headPointer = headPointer()
//        tailPointer = tailPointer()
//        head = head()
//        tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.Two.note, head.value)
//        assertEquals(FakeNotes.One.note, tail.value)
//        assertEquals(2, memoryLruStore.cache.size)
//
//        val result = memoryLruStore.read(FakeNotes.One.key)
//
//        assertEquals(FakeNotes.One.note, result.last())
//
//        headPointer = headPointer()
//        tailPointer = tailPointer()
//        head = head()
//        tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.One.note, head.value)
//        assertEquals(FakeNotes.Two.note, tail.value)
//        assertEquals(2, memoryLruStore.cache.size)
//    }
//
//    @Test
//    fun write10ShouldNotRemoveAny() = testScope.runTest {
//
//        FakeNotes.listN(10).forEach {
//            memoryLruStore.write(it.key, it.note)
//        }
//
//        testScope.advanceUntilIdle()
//
//        val headPointer = headPointer()
//        val tailPointer = tailPointer()
//        val head = head()
//        val tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.Ten.note, head.value)
//        assertEquals(FakeNotes.One.note, tail.value)
//        assertEquals(10, memoryLruStore.cache.size)
//    }
//
//    @Test
//    fun write11ShouldRemoveFirst() = testScope.runTest {
//
//        FakeNotes.listN(11).forEach {
//            memoryLruStore.write(it.key, it.note)
//        }
//
//        testScope.advanceUntilIdle()
//
//        val headPointer = headPointer()
//        val tailPointer = tailPointer()
//        val head = head()
//        val tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.Eleven.note, head.value)
//        assertEquals(FakeNotes.Two.note, tail.value)
//        assertEquals(10, memoryLruStore.cache.size)
//    }
//
//    @Test
//    fun delete() = testScope.runTest {
//
//        FakeNotes.listN(10).forEach {
//            memoryLruStore.write(it.key, it.note)
//        }
//
//        memoryLruStore.delete(FakeNotes.Ten.key)
//        memoryLruStore.delete(FakeNotes.Nine.key)
//        memoryLruStore.delete(FakeNotes.One.key)
//
//        testScope.advanceUntilIdle()
//
//        val headPointer = headPointer()
//        val tailPointer = tailPointer()
//        val head = head()
//        val tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//        assertEquals(FakeNotes.Eight.note, head.value)
//        assertEquals(FakeNotes.Two.note, tail.value)
//        assertEquals(7, memoryLruStore.cache.size)
//
//        assertEquals(null, memoryLruStore.cache[FakeNotes.Ten.key])
//        assertEquals(null, memoryLruStore.cache[FakeNotes.Nine.key])
//        assertEquals(null, memoryLruStore.cache[FakeNotes.One.key])
//    }
//
//    @Test
//    fun clear() = testScope.runTest {
//
//        FakeNotes.list().forEach {
//            memoryLruStore.write(it.key, it.note)
//        }
//
//        memoryLruStore.clear()
//
//        testScope.advanceUntilIdle()
//
//        val headPointer = headPointer()
//        val tailPointer = tailPointer()
//        val head = head()
//        val tail = tail()
//
//        assertEquals(MemoryLruStore.headPointer, headPointer)
//        assertEquals(MemoryLruStore.tailPointer, tailPointer)
//
//        assertEquals<Any>(MemoryLruStore.headPointer.value, tail.value)
//        assertEquals<Any>(MemoryLruStore.tailPointer.value, head.value)
//        assertEquals(0, memoryLruStore.cache.size)
//    }
//}
