package com.dropbox.store

import com.dropbox.store.fake.FakeDb
import com.dropbox.store.fake.FakeFactory
import com.dropbox.store.fake.FakeNotes
import com.dropbox.store.fake.OkTestMarket
import com.dropbox.store.fake.api.FakeApi
import com.dropbox.store.fake.model.Note
import com.dropbox.store.impl.ShareableLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
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
    private lateinit var memoryLruCache: ShareableLruCache
    private lateinit var factory: FakeFactory<String, Note, Note>

    @BeforeTest
    fun before() {
        api = FakeApi()
        market = OkTestMarket.build(testScope)
        db = OkTestMarket.db
        memoryLruCache = OkTestMarket.memoryLruCache
        factory = FakeFactory(api)
    }

    @Test
    fun emptyStoreWithWriteRequestQueueShouldNotTryToEagerlyResolve() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val newNote1 = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest1 = factory.buildWriter<Note>(FakeNotes.One.key, newNote1, fail = true)

        val sharedFlow = market.read(readRequest)
        market.write(writeRequest1)

        market.delete(FakeNotes.One.key)
        val shouldBeEmpty = sharedFlow.replayCache.last()
        assertIs<Market.Response.Empty>(shouldBeEmpty)

        val newNote2 = newNote1.copy(content = "New Content")
        val writeRequest2 = factory.buildWriter<Note>(FakeNotes.One.key, newNote2, fail = true)
        market.write(writeRequest2)

        val refreshRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true, fail = false)

        market.read(refreshRequest)
        val last = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)
    }

    @Test
    fun nonEmptyStoreWithWriteRequestQueueShouldTryToEagerlyResolve() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val newNote1 = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest1 = factory.buildWriter<Note>(FakeNotes.One.key, newNote1, fail = true)

        val waitingForSharedFlow = async { market.read(readRequest) }
        val sharedFlow = waitingForSharedFlow.await()
        advanceUntilIdle()

        market.write(writeRequest1)

        val newNote2 = newNote1.copy(content = "New Content")
        val writeRequest2 = factory.buildWriter<Note>(FakeNotes.One.key, newNote2, fail = true)
        market.write(writeRequest2)
        val refreshRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true)
        market.read(refreshRequest)
        advanceUntilIdle()

        val last = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(newNote2, last.value)
        assertEquals(Market.Response.Companion.Origin.Remote, last.origin)
    }


    @Test
    fun nonEmptyStoreShouldNotBeOverwrittenByFailedFetch() = testScope.runTest {
        val successReadRequest = factory.buildReader<Note>(FakeNotes.One.key, fail = false, refresh = true)
        val sharedFlowAsync = async { market.read(successReadRequest) }
        val sharedFlow = sharedFlowAsync.await()
        advanceUntilIdle()
        val last1 = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(last1)
        assertEquals(FakeNotes.One.note, last1.value)
        assertEquals(Market.Response.Companion.Origin.Remote, last1.origin)

        val failureReadRequest = factory.buildReader<Note>(FakeNotes.One.key, fail = true, refresh = true)
        market.read(failureReadRequest)
        advanceUntilIdle()
        val last2 = sharedFlow.replayCache.last()
        assertIs<Market.Response.Failure>(last2)

        val last2Success = sharedFlow.replayCache.filterIsInstance<Market.Response.Success<Note>>().last()
        assertEquals(Market.Response.Companion.Origin.Store, last2Success.origin)
        assertEquals(FakeNotes.One.note, last2Success.value)

        val memoryLruCacheValueAsync = async { memoryLruCache.read<Note>(FakeNotes.One.key).last() }
        val memoryLruCacheValue = memoryLruCacheValueAsync.await()
        assertEquals(FakeNotes.One.note, memoryLruCacheValue)

        val dbValueAsync = async { db.read<Note>(FakeNotes.One.key).last() }
        val dbValue = dbValueAsync.await()
        assertEquals(FakeNotes.One.note, dbValue)
    }
}

