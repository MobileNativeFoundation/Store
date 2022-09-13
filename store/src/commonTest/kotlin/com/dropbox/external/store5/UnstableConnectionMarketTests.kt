package com.dropbox.external.store5

import com.dropbox.external.store5.fake.FakeDb
import com.dropbox.external.store5.fake.FakeFactory
import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.OkTestMarket
import com.dropbox.external.store5.fake.api.FakeApi
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class UnstableConnectionMarketTests {
    private val testScope = TestScope()
    private lateinit var api: FakeApi
    private lateinit var market: Market<String>
    private lateinit var db: FakeDb
    private lateinit var memoryLruCache: MemoryLruCache
    private lateinit var factory: FakeFactory<String, Note, Note>

    @BeforeTest
    fun before() {
        api = FakeApi()
        market = OkTestMarket.build()
        db = OkTestMarket.db
        memoryLruCache = OkTestMarket.memoryLruCache
        factory = FakeFactory(api)
        db.reset()
    }

    @Test
    fun emptyStoreWithWriteRequestQueueShouldNotTryToEagerlyResolve() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.Seven.key)

        val newNote1 = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest1 = factory.buildWriter<Note>(FakeNotes.Seven.key, newNote1, fail = true)

        val flow = market.read(readRequest)
        val writeAsync = async { market.write(writeRequest1) }
        writeAsync.await()
        advanceUntilIdle()

        market.delete(FakeNotes.Seven.key)
        val shouldBeEmpty = flow.take(5).last()
        assertIs<MarketResponse.Empty>(shouldBeEmpty)

        val newNote2 = newNote1.copy(content = "New Content")
        val writeRequest2 = factory.buildWriter<Note>(FakeNotes.Seven.key, newNote2, fail = true)
        market.write(writeRequest2)

        market.delete(FakeNotes.Seven.key)
        testScope.advanceUntilIdle()

        val refreshRequest = factory.buildReader<Note>(FakeNotes.Seven.key, refresh = true, fail = false)

        market.read(refreshRequest)
        val last = flow.take(9).last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(FakeNotes.Seven.note, last.value)
    }

    @Test
    fun nonEmptyStoreWithWriteRequestQueueShouldTryToEagerlyResolve() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.Three.key)

        val newNote1 = FakeNotes.Three.note.copy(title = "New Title")
        val writeRequest1 = factory.buildWriter<Note>(FakeNotes.Three.key, newNote1, fail = true)

        val waitingForSharedFlow = async { market.read(readRequest) }
        val flow = waitingForSharedFlow.await()
        advanceUntilIdle()

        market.write(writeRequest1)

        val newNote2 = newNote1.copy(content = "New Content")
        val writeRequest2 = factory.buildWriter<Note>(FakeNotes.Three.key, newNote2, fail = true)
        market.write(writeRequest2)
        val refreshRequest = factory.buildReader<Note>(FakeNotes.Three.key, refresh = true)
        market.read(refreshRequest)
        advanceUntilIdle()
        println(1)

        val last = flow.take(7).last()
        println(2)
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNote2, last.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, last.origin)
    }


    @Test
    fun nonEmptyStoreShouldNotBeOverwrittenByFailedFetch() = testScope.runTest {
        val successReadRequest = factory.buildReader<Note>(FakeNotes.Two.key, fail = false, refresh = true)
        val flowAsync = async { market.read(successReadRequest) }
        val flow = flowAsync.await()
        advanceUntilIdle()
        val last1 = flow.take(3).last()
        assertIs<MarketResponse.Success<Note>>(last1)
        assertEquals(FakeNotes.Two.note, last1.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, last1.origin)

        val failureReadRequest = factory.buildReader<Note>(FakeNotes.Two.key, fail = true, refresh = true)
        market.read(failureReadRequest)
        advanceUntilIdle()
        val last2 = flow.take(5).last()
        assertIs<MarketResponse.Failure>(last2)

        val last2Success = flow.take(5).filterIsInstance<MarketResponse.Success<Note>>().last()
        assertEquals(MarketResponse.Companion.Origin.Store, last2Success.origin)
        assertEquals(FakeNotes.Two.note, last2Success.value)

        val memoryLruCacheValueAsync = async { memoryLruCache.read<Note>(FakeNotes.Two.key).last() }
        val memoryLruCacheValue = memoryLruCacheValueAsync.await()
        assertEquals(FakeNotes.Two.note, memoryLruCacheValue)

        val dbValueAsync = async { db.read<Note>(FakeNotes.Two.key).last() }
        val dbValue = dbValueAsync.await()
        assertEquals(FakeNotes.Two.note, dbValue)
    }
}

