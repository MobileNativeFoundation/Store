package com.dropbox.external.store5

import com.dropbox.external.store5.fake.FakeDb
import com.dropbox.external.store5.fake.FakeFactory
import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.OkTestMarket
import com.dropbox.external.store5.fake.api.FakeApi
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.ShareableLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectMarketTests {
    private val testScope = TestScope()
    private lateinit var api: FakeApi
    private lateinit var market: Market<String>
    private lateinit var db: FakeDb
    private lateinit var memoryLruCache: ShareableLruCache
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
    fun eagerlyResolveConflictsWithEmptyStore() = testScope.runTest {
        val readRequest = factory.buildReader<Note>(FakeNotes.One.key, refresh = true)
        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writeRequest = factory.buildWriter<Note>(FakeNotes.One.key, newNote)

        market.read(readRequest)
        market.write(writeRequest)
        market.delete(FakeNotes.One.key)

        val response = market.read(readRequest)
        testScope.advanceUntilIdle()
        val last = response.replayCache.last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(newNote, last.value)
    }
}