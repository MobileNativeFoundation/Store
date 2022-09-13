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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMarketTests {
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
    fun failWriteAfterInit() = testScope.runTest {

        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val readResponse = testScope.async { market.read(readRequest) }
        val flow = readResponse.await()

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        testScope.advanceUntilIdle()

        val last = flow.toList().last()

        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNote, last.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, last.origin)
    }

    @Test
    fun failRefresh() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true, fail = true)

        val readResponse = async { market.read(readRequest) }
        val flow = readResponse.await()
        val first = flow.toList().first()
        assertIs<MarketResponse.Loading>(first)

        testScope.advanceUntilIdle()

        val second = flow.toList().last()
        assertIs<MarketResponse.Failure>(second)
        assertEquals(MarketResponse.Companion.Origin.Remote, second.origin)

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        val lastSuccess = flow.toList().findLast { it is MarketResponse.Success<Note> }

        assertIs<MarketResponse.Success<Note>>(lastSuccess)
        assertEquals(newNote, lastSuccess.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, lastSuccess.origin)
    }

    @Test
    fun onCompletionFailRefresh() = testScope.runTest {

        var readErrorsHandled = 0

        val onCompletion = OnMarketCompletion<Note>(
            onSuccess = {},
            onFailure = { readErrorsHandled++ }
        )

        val readRequest = factory.buildReader<Note>(
            key = FakeNotes.One.key,
            refresh = true,
            fail = true
        ) { listOf(onCompletion) }

        val readResponse = async { market.read(readRequest) }
        val flow = readResponse.await()
        val first = flow.toList().first()
        assertIs<MarketResponse.Loading>(first)

        testScope.advanceUntilIdle()

        val second = flow.toList().last()
        assertIs<MarketResponse.Failure>(second)
        assertEquals(MarketResponse.Companion.Origin.Remote, second.origin)

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        val lastSuccess = flow.toList().findLast { it is MarketResponse.Success<Note> }

        assertIs<MarketResponse.Success<Note>>(lastSuccess)
        assertEquals(newNote, lastSuccess.value)
        assertEquals(MarketResponse.Companion.Origin.LocalWrite, lastSuccess.origin)
        assertEquals(1, readErrorsHandled)
    }
}
