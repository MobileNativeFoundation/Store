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
    fun failWriteAfterInit() = testScope.runTest {

        val readRequest = factory.buildReader<Note>(FakeNotes.One.key)

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        val last = sharedFlow.replayCache.last()

        assertIs<Market.Response.Success<Note>>(last)
        assertEquals(newNote, last.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, last.origin)
    }

    @Test
    fun failRefresh() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true, fail = true)

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()
        val first = sharedFlow.replayCache.first()
        assertIs<Market.Response.Loading>(first)

        testScope.advanceUntilIdle()

        val second = sharedFlow.replayCache.last()
        assertIs<Market.Response.Failure>(second)
        assertEquals(Market.Response.Companion.Origin.Remote, second.origin)

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write<Note, Note>(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        val lastSuccess = sharedFlow.replayCache.findLast { it is Market.Response.Success<Note> }

        assertIs<Market.Response.Success<Note>>(lastSuccess)
        assertEquals(newNote, lastSuccess.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, lastSuccess.origin)
    }

    @Test
    fun onCompletionFailRefresh() = testScope.runTest {

        var readErrorsHandled = 0

        val onCompletion = Market.Request.Read.OnCompletion.Builder<Note>()
            .onFailure { readErrorsHandled++ }
            .build()

        val readRequest = factory.buildReader<Note>(
            key = FakeNotes.One.key,
            refresh = true,
            fail = true
        ) { listOf(onCompletion) }

        val readResponse = async { market.read<Note, Note>(readRequest) }
        val sharedFlow = readResponse.await()
        val first = sharedFlow.replayCache.first()
        assertIs<Market.Response.Loading>(first)

        testScope.advanceUntilIdle()

        val second = sharedFlow.replayCache.last()
        assertIs<Market.Response.Failure>(second)
        assertEquals(Market.Response.Companion.Origin.Remote, second.origin)

        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote, fail = true)

        val writeResponse = async { market.write<Note, Note>(writeRequest) }
        val isSuccess = writeResponse.await()

        assertEquals(false, isSuccess)

        val lastSuccess = sharedFlow.replayCache.findLast { it is Market.Response.Success<Note> }

        assertIs<Market.Response.Success<Note>>(lastSuccess)
        assertEquals(newNote, lastSuccess.value)
        assertEquals(Market.Response.Companion.Origin.LocalWrite, lastSuccess.origin)
        assertEquals(1, readErrorsHandled)
    }
}
