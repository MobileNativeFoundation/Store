@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dropbox.store

import com.dropbox.store.fake.FakeDb
import com.dropbox.store.fake.FakeFactory
import com.dropbox.store.fake.OkTestMarket
import com.dropbox.store.fake.FakeNotes
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
import kotlin.test.assertFails
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MarketTests {
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
    fun readEmpty() = testScope.runTest {
        val request = factory.buildReader<Note>(FakeNotes.One.key)

        val response = async { market.read<Note, Note>(request) }
        val sharedFlow = response.await()

        val first = sharedFlow.replayCache.first()
        assertIs<Market.Response.Loading>(first)

        testScope.advanceUntilIdle()

        val last = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)

        val dbResult = db.read<Note>(FakeNotes.One.key)
        assertEquals(FakeNotes.One.note, dbResult.last())

        val memoryLruCacheResult = memoryLruCache.read<Note>(FakeNotes.One.key)
        assertEquals(FakeNotes.One.note, memoryLruCacheResult.last())
    }

    @Test
    fun read() = testScope.runTest {
        val request = factory.buildReader<Note>(FakeNotes.One.key)

        val response = async { market.read<Note, Note>(request) }
        val sharedFlow = response.await()

        testScope.advanceUntilIdle()

        val last = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)
        assertEquals(Market.Response.Companion.Origin.Remote, last.origin)
    }

    @Test
    fun readMultipleRequests() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read<Note, Note>(requestOne) }
        val sharedFlowOne = responseOne.await()

        testScope.advanceUntilIdle()

        val responseTwo = async { market.read<Note, Note>(requestTwo) }
        val sharedFlowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = sharedFlowOne.replayCache.last()
        assertIs<Market.Response.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)
        assertEquals(Market.Response.Companion.Origin.Remote, lastOne.origin)

        val lastTwo = sharedFlowTwo.replayCache.last()
        assertIs<Market.Response.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)
        assertEquals(Market.Response.Companion.Origin.Remote, lastTwo.origin)
    }

    @Test
    fun writeEmpty() = testScope.runTest {

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val request = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        assertFails {
            market.write(request)
        }
    }

    @Test
    fun writeAfterInit() = testScope.runTest {

        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(true, isSuccess)

        val last = sharedFlow.replayCache.last()

        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(newNote, last.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, last.origin)
        assertEquals(newNote, api.get(FakeNotes.One.key))
    }

    @Test
    fun writeMultipleRequests() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()

        val newNoteOneA = FakeNotes.One.note.copy(title = "New Title - A")
        val newNoteOneB = FakeNotes.One.note.copy(title = "New Title - B")
        val writeRequestOneA = factory.buildWriter<Note>(FakeNotes.One.key, newNoteOneA)
        val writeRequestOneB = factory.buildWriter<Note>(FakeNotes.One.key, newNoteOneB)
        market.write(writeRequestOneA)
        market.write(writeRequestOneB)

        val newNoteTwo = newNoteOneA.copy(content = "New Content")
        val writeRequestTwo = factory.buildWriter<Note>(FakeNotes.One.key, newNoteTwo)
        val writeResponseTwo = async { market.write(writeRequestTwo) }

        val isSuccess = writeResponseTwo.await()

        assertEquals(true, isSuccess)

        val last = sharedFlow.replayCache.last()

        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(newNoteTwo, last.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, last.origin)
        assertEquals(newNoteTwo, api.get(FakeNotes.One.key))
    }

    @Test
    fun onCompletionMultipleWriteRequestsWithFailures() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val marketCompleted = mutableMapOf<String, Int>()
        val postCompleted = mutableMapOf<String, Int>()

        val marketOnCompletion = Market.Request.Write.OnCompletion.Builder<Note>()
            .onSuccess { response ->
                val countBefore = marketCompleted[response.value.id] ?: 0
                val countAfter = countBefore + 1
                marketCompleted[response.value.id] = countAfter
            }
            .build()

        val postOnCompletion = Fetch.OnCompletion.Builder<Note>()
            .onSuccess { response ->
                val countBefore = postCompleted[response.value.id] ?: 0
                val countAfter = countBefore + 1
                postCompleted[response.value.id] = countAfter
            }
            .build()

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()

        val newNoteOneA = FakeNotes.One.note.copy(title = "New Title - A")
        val newNoteOneB = FakeNotes.One.note.copy(title = "New Title - B")
        val writeRequestOneA = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteOneA,
            fail = true,
            postOnCompletion = postOnCompletion
        ) { listOf(marketOnCompletion) }
        val writeRequestOneB = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteOneB,
            postOnCompletion = postOnCompletion
        ) { listOf(marketOnCompletion) }
        market.write(writeRequestOneA)
        testScope.advanceUntilIdle()
        val firstResult = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(firstResult)
        assertEquals(Market.Response.Companion.Origin.Remote, firstResult.origin)
        assertEquals(FakeNotes.One.note, firstResult.value)
        assertEquals(null, marketCompleted[FakeNotes.One.note.id])
        assertEquals(null, postCompleted[FakeNotes.One.note.id])

        market.write(writeRequestOneB)
        testScope.advanceUntilIdle()
        val secondResult = sharedFlow.replayCache.last()
        assertIs<Market.Response.Success<Note>>(secondResult)
        assertEquals(1, marketCompleted[FakeNotes.One.note.id])
        assertEquals(2, postCompleted[FakeNotes.One.note.id])

        val newNoteTwo = newNoteOneA.copy(content = "New Content")
        val writeRequestTwo = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteTwo,
            postOnCompletion = postOnCompletion
        ) {
            listOf(marketOnCompletion)
        }
        val writeResponseTwo = async { market.write(writeRequestTwo) }

        val isSuccess = writeResponseTwo.await()
        assertEquals(true, isSuccess)

        testScope.advanceUntilIdle()
        val lastResult = sharedFlow.replayCache.last()

        assertIs<Market.Response.Success<Note>>(lastResult)
        assertEquals(newNoteTwo, lastResult.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, lastResult.origin)
        assertEquals(2, marketCompleted[FakeNotes.One.note.id])
        assertEquals(3, postCompleted[FakeNotes.One.note.id])

        assertEquals(newNoteTwo, api.get(FakeNotes.One.key))
    }

    @Test
    fun delete() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read<Note, Note>(requestOne) }
        val sharedFlowOne = responseOne.await()

        val responseTwo = async { market.read<Note, Note>(requestTwo) }
        val sharedFlowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = sharedFlowOne.replayCache.last()
        val lastTwo = sharedFlowTwo.replayCache.last()

        assertIs<Market.Response.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)

        assertIs<Market.Response.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)

        market.delete(FakeNotes.One.key)
        market.delete(FakeNotes.Two.key)

        testScope.advanceUntilIdle()

        val lastOneAfterDelete = sharedFlowOne.replayCache.last()
        val lastTwoAfterDelete = sharedFlowTwo.replayCache.last()

        assertIs<Market.Response.Empty>(lastOneAfterDelete)
        assertIs<Market.Response.Empty>(lastTwoAfterDelete)
    }

    @Test
    fun clear() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read<Note, Note>(requestOne) }
        val sharedFlowOne = responseOne.await()

        val responseTwo = async { market.read<Note, Note>(requestTwo) }
        val sharedFlowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = sharedFlowOne.replayCache.last()
        val lastTwo = sharedFlowTwo.replayCache.last()

        assertIs<Market.Response.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)

        assertIs<Market.Response.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)

        market.clear()

        testScope.advanceUntilIdle()

        val lastOneAfterDelete = sharedFlowOne.replayCache.last()
        val lastTwoAfterDelete = sharedFlowTwo.replayCache.last()

        assertIs<Market.Response.Empty>(lastOneAfterDelete)
        assertIs<Market.Response.Empty>(lastTwoAfterDelete)
    }

    @Test
    fun onCompleteRefresh() = testScope.runTest {

        val completed = mutableMapOf<String, Boolean>()

        val onCompletion = Market.Request.Read.OnCompletion.Builder<Note>()
            .onSuccess { response -> completed[response.value.id] = true }
            .build()

        val requestOne = factory.buildReader<Note>(FakeNotes.One.key, refresh = true) { listOf(onCompletion) }
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key, refresh = true) { listOf(onCompletion) }

        val responseOne = async { market.read<Note, Note>(requestOne) }
        val sharedFlowOne = responseOne.await()

        testScope.advanceUntilIdle()

        val responseTwo = async { market.read<Note, Note>(requestTwo) }
        val sharedFlowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = sharedFlowOne.replayCache.last()
        assertIs<Market.Response.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)
        assertEquals(Market.Response.Companion.Origin.Remote, lastOne.origin)

        val lastTwo = sharedFlowTwo.replayCache.last()
        assertIs<Market.Response.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)
        assertEquals(Market.Response.Companion.Origin.Remote, lastTwo.origin)

        assertEquals(true, completed[FakeNotes.One.note.id])
        assertEquals(true, completed[FakeNotes.Two.note.id])
    }

    @Test
    fun onCompleteWrite() = testScope.runTest {

        val completed = mutableMapOf<String, Boolean>()

        val onCompletion = Market.Request.Write.OnCompletion.Builder<Note>()
            .onSuccess { response -> completed[response.value.id] = true }
            .build()

        val readRequestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val writeRequestOne =
            factory.buildWriter<Note>(FakeNotes.One.key, FakeNotes.One.note.copy(title = "NEW TITLE")) {
                listOf(onCompletion)
            }

        val readRequestTwo = factory.buildReader<Note>(FakeNotes.Two.key)
        val writeRequestTwo =
            factory.buildWriter<Note>(FakeNotes.Two.key, FakeNotes.Two.note.copy(title = "NEW TITLE")) {
                listOf(onCompletion)
            }

        market.read<Note, Note>(readRequestOne)
        market.read<Note, Note>(readRequestTwo)

        testScope.advanceUntilIdle()

        market.write(writeRequestOne)
        market.write(writeRequestTwo)

        testScope.advanceUntilIdle()

        assertEquals(true, completed[FakeNotes.One.note.id])
        assertEquals(true, completed[FakeNotes.Two.note.id])
    }
}
