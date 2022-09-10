package com.dropbox.external.store5

import com.dropbox.external.store5.fake.FakeDb
import com.dropbox.external.store5.fake.FakeFactory
import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.OkTestMarket
import com.dropbox.external.store5.fake.api.FakeApi
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import com.dropbox.external.store5.impl.SomeBroadcast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs


@OptIn(ExperimentalCoroutinesApi::class)
class MarketMultithreadedAndroidTests {
    private val testScope = TestScope()
    private lateinit var api: FakeApi
    private lateinit var market: Market<String>
    private lateinit var db: FakeDb
    private lateinit var memoryLruCache: MemoryLruCache
    private lateinit var factory: FakeFactory<String, Note, Note>

    @Before
    fun before() {
        api = FakeApi()
        market = OkTestMarket.build()
        db = OkTestMarket.db
        memoryLruCache = OkTestMarket.memoryLruCache
        factory = FakeFactory(api)
    }

    @Test
    fun write() = testScope.runTest {
        val threads = mutableListOf<Thread>()
        for (i in 0..10) {
            threads.add(thread(start = true) {})
        }

        val notes = FakeNotes.list()
        val newNotes = notes.map { it.note.copy(title = "New Title") }
        val readRequests = notes.map { factory.buildReader<Note>(it.key) }

        val readResponses = mutableListOf<SomeBroadcast<Note>>()

        withContext(testScope.coroutineContext) {
            for (i in 0..10) {
                val note = notes[i]
                val currentThread = threads[i]
                val newNote = newNotes[i]
                val writeRequest = factory.buildWriter<Note>(note.key, newNote)
                currentThread.run {
                    val readResponse = market.read(readRequests[i])
                    readResponses.add(readResponse)
                    market.write(writeRequest)
                }
            }
        }

        notes.forEachIndexed { index, note ->
            val response = readResponses[index]
            val last = response.replayCache.last()
            assertIs<MarketResponse.Success<Note>>(last)
            assertEquals(newNotes[index], last.value)
            assertEquals(MarketResponse.Companion.Origin.LocalWrite, last.origin)
            assertEquals(newNotes[index], api.get(note.key))
        }
    }
}
