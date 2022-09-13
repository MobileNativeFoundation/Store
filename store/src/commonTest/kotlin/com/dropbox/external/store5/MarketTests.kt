@file:OptIn(ExperimentalCoroutinesApi::class)

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
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    private lateinit var memoryLruCache: MemoryLruCache
    private lateinit var factory: FakeFactory<String, Note, Note>

    @BeforeTest
    fun before() {
        api = FakeApi()
        market = OkTestMarket.build()
        db = OkTestMarket.db
        memoryLruCache = OkTestMarket.memoryLruCache
        factory = FakeFactory(api)
    }

    @Test
    fun readEmpty() = testScope.runTest {
        val request = factory.buildReader<Note>(FakeNotes.One.key)

        val response = async { market.read(request) }
        val flow = response.await()
        val responses = flow.take(3).toList()

        val first = responses.first()
        assertIs<MarketResponse.Loading>(first)

        testScope.advanceUntilIdle()

        val last = responses.last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)

        val dbResult = db.read<Note>(FakeNotes.One.key)
        assertEquals(FakeNotes.One.note, dbResult.last())

        val memoryLruCacheResult = memoryLruCache.read<Note>(FakeNotes.One.key)
        assertEquals(FakeNotes.One.note, memoryLruCacheResult.last())
    }

    @Test
    fun read() = testScope.runTest {
        val request = factory.buildReader<Note>(FakeNotes.One.key)

        val response = async { market.read(request) }
        val flow = response.await()
        val responses = flow.take(3).toList()

        testScope.advanceUntilIdle()

        val last = responses.last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, last.origin)
    }

    @Test
    fun readMultipleRequests() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read(requestOne) }
        val flowOne = responseOne.await()
        val responsesOne = flowOne.take(3).toList()
        testScope.advanceUntilIdle()

        val responseTwo = async { market.read(requestTwo) }
        val flowTwo = responseTwo.await()
        val responsesTwo = flowTwo.take(3).toList()
        testScope.advanceUntilIdle()

        val lastOne = responsesOne.last()
        assertIs<MarketResponse.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, lastOne.origin)

        val lastTwo = responsesTwo.last()
        assertIs<MarketResponse.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, lastTwo.origin)
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

        val readResponse = async { market.read(readRequest) }
        val flow = readResponse.await()

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        val responsesBeforeWrite = flow.take(3).toList()

        val secondToLast = responsesBeforeWrite.last()
        assertIs<MarketResponse.Success<Note>>(secondToLast)
        assertEquals(FakeNotes.One.note, secondToLast.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, secondToLast.origin)
        assertEquals(FakeNotes.One.note, api.get(FakeNotes.One.key))

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(true, isSuccess)

        val responsesAfterWrite = flow.take(4).toList()
        val last = responsesAfterWrite.last()

        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNote, last.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, last.origin)
        assertEquals(newNote, api.get(FakeNotes.One.key))
    }

    @Test
    fun writeMultipleRequests() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val readResponse = async { market.read(readRequest) }
        val flow = readResponse.await()

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

        val responses = flow.toList()
        val last = responses.last()

        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNoteTwo, last.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, last.origin)
        assertEquals(newNoteTwo, api.get(FakeNotes.One.key))
    }

    @Test
    fun onCompletionMultipleWriteRequestsWithFailures() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val marketCompleted = mutableMapOf<String, Int>()
        val postCompleted = mutableMapOf<String, Int>()

        val marketOnCompletion = OnMarketCompletion<Note>(
            onSuccess = { response ->
                val countBefore = marketCompleted[response.value.id] ?: 0
                val countAfter = countBefore + 1
                marketCompleted[response.value.id] = countAfter
            },
            onFailure = {}
        )

        val postOnCompletion = OnRemoteCompletion<Note>(
            onSuccess = { response ->
                val countBefore = postCompleted[response.value.id] ?: 0
                val countAfter = countBefore + 1
                postCompleted[response.value.id] = countAfter
            },
            onFailure = {}
        )

        val readResponse = async { market.read(readRequest) }
        val flow = readResponse.await()

        val newNoteOneA = FakeNotes.One.note.copy(title = "New Title - A")
        val newNoteOneB = FakeNotes.One.note.copy(title = "New Title - B")
        val writeRequestOneA = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteOneA,
            fail = true,
            onCompletionsProducer = { listOf(marketOnCompletion) },
            postOnCompletion = postOnCompletion
        )
        val writeRequestOneB = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteOneB,
            onCompletionsProducer = { listOf(marketOnCompletion) },
            postOnCompletion = postOnCompletion
        )
        market.write(writeRequestOneA)
        testScope.advanceUntilIdle()
        val firstResult = flow.toList().first()
        assertIs<MarketResponse.Loading>(firstResult)

        val firstSuccessResult = flow.toList().filterIsInstance<MarketResponse.Success<Note>>().first()
        assertEquals(MarketResponse.Companion.Origin.Remote, firstSuccessResult.origin)
        assertEquals(FakeNotes.One.note, firstSuccessResult.value)
        assertEquals(null, marketCompleted[FakeNotes.One.note.id])
        assertEquals(null, postCompleted[FakeNotes.One.note.id])

        market.write(writeRequestOneB)
        testScope.advanceUntilIdle()
        val secondResult = flow.toList().last()
        assertIs<MarketResponse.Success<Note>>(secondResult)
        assertEquals(1, marketCompleted[FakeNotes.One.note.id])
        assertEquals(2, postCompleted[FakeNotes.One.note.id])

        val newNoteTwo = newNoteOneA.copy(content = "New Content")
        val writeRequestTwo = factory.buildWriter<Note>(
            key = FakeNotes.One.key,
            input = newNoteTwo,
            onCompletionsProducer = { listOf(marketOnCompletion) },
            postOnCompletion = postOnCompletion
        )
        val writeResponseTwo = async { market.write(writeRequestTwo) }

        val isSuccess = writeResponseTwo.await()
        assertEquals(true, isSuccess)

        testScope.advanceUntilIdle()
        val lastResult = flow.toList().last()

        assertIs<MarketResponse.Success<Note>>(lastResult)
        assertEquals(newNoteTwo, lastResult.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, lastResult.origin)
        assertEquals(2, marketCompleted[FakeNotes.One.note.id])
        assertEquals(3, postCompleted[FakeNotes.One.note.id])

        assertEquals(newNoteTwo, api.get(FakeNotes.One.key))
    }

    @Test
    fun delete() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read(requestOne) }
        val flowOne = responseOne.await()

        val responseTwo = async { market.read(requestTwo) }
        val flowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = flowOne.toList().last()
        val lastTwo = flowTwo.toList().last()

        assertIs<MarketResponse.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)

        assertIs<MarketResponse.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)

        market.delete(FakeNotes.One.key)
        market.delete(FakeNotes.Two.key)

        testScope.advanceUntilIdle()

        val lastOneAfterDelete = flowOne.toList().last()
        val lastTwoAfterDelete = flowTwo.toList().last()

        assertIs<MarketResponse.Empty>(lastOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastTwoAfterDelete)
    }

    @Test
    fun clear() = testScope.runTest {
        val requestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key)

        val responseOne = async { market.read(requestOne) }
        val flowOne = responseOne.await()

        val responseTwo = async { market.read(requestTwo) }
        val flowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = flowOne.toList().last()
        val lastTwo = flowTwo.toList().last()

        assertIs<MarketResponse.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)

        assertIs<MarketResponse.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)

        market.delete()

        testScope.advanceUntilIdle()

        val lastOneAfterDelete = flowOne.toList().last()
        val lastTwoAfterDelete = flowTwo.toList().last()

        assertIs<MarketResponse.Empty>(lastOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastTwoAfterDelete)
    }

    @Test
    fun onCompleteRefresh() = testScope.runTest {

        val completed = mutableMapOf<String, Boolean>()

        val onCompletion = OnMarketCompletion<Note>(
            onSuccess = { response -> completed[response.value.id] = true },
            onFailure = {}
        )

        val requestOne = factory.buildReader<Note>(FakeNotes.One.key, refresh = true) { listOf(onCompletion) }
        val requestTwo = factory.buildReader<Note>(FakeNotes.Two.key, refresh = true) { listOf(onCompletion) }

        val responseOne = async { market.read(requestOne) }
        val flowOne = responseOne.await()

        testScope.advanceUntilIdle()

        val responseTwo = async { market.read(requestTwo) }
        val flowTwo = responseTwo.await()

        testScope.advanceUntilIdle()

        val lastOne = flowOne.toList().last()
        assertIs<MarketResponse.Success<Note>>(lastOne)
        assertEquals(FakeNotes.One.note, lastOne.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, lastOne.origin)

        val lastTwo = flowTwo.toList().last()
        assertIs<MarketResponse.Success<Note>>(lastTwo)
        assertEquals(FakeNotes.Two.note, lastTwo.value)
        assertEquals(MarketResponse.Companion.Origin.Remote, lastTwo.origin)

        assertEquals(true, completed[FakeNotes.One.note.id])
        assertEquals(true, completed[FakeNotes.Two.note.id])
    }

    @Test
    fun onCompleteWrite() = testScope.runTest {

        val completed = mutableMapOf<String, Boolean>()

        val onCompletion = OnMarketCompletion<Note>(
            onSuccess = { response -> completed[response.value.id] = true },
            onFailure = {}
        )
        val readRequestOne = factory.buildReader<Note>(FakeNotes.One.key)
        val writeRequestOne =
            factory.buildWriter<Note>(
                key = FakeNotes.One.key,
                input = FakeNotes.One.note.copy(title = "NEW TITLE"),
                fail = false,
                onCompletionsProducer = { listOf(onCompletion) }
            )

        val readRequestTwo = factory.buildReader<Note>(FakeNotes.Two.key)
        val writeRequestTwo =
            factory.buildWriter<Note>(
                key = FakeNotes.Two.key,
                input = FakeNotes.Two.note.copy(title = "NEW TITLE"),
                fail = false,
                onCompletionsProducer = { listOf(onCompletion) }
            )

        market.read(readRequestOne)
        market.read(readRequestTwo)

        testScope.advanceUntilIdle()

        market.write(writeRequestOne)
        market.write(writeRequestTwo)

        testScope.advanceUntilIdle()

        assertEquals(
            true,
            completed[FakeNotes.One.note.id]
        )
        assertEquals(true, completed[FakeNotes.Two.note.id])
    }

    @Test
    fun readWithoutRefreshAfterDeleteShouldReturnEmpty() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)
        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        market.read(readRequest)
        market.write(writeRequest)
        market.delete(FakeNotes.One.key)

        val response = market.read(readRequest)
        testScope.advanceUntilIdle()
        val last = response.toList().last()
        assertIs<MarketResponse.Empty>(last)
    }

    @Test
    fun readWithRefreshAfterDeleteShouldReturnRemote() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true)
        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        market.read(readRequest)
        market.write(writeRequest)
        market.delete(FakeNotes.One.key)

        val response = market.read(readRequest)
        testScope.advanceUntilIdle()
        val last = response.toList().last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNote, last.value)
    }
}
