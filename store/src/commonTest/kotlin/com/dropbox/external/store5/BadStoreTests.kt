package com.dropbox.external.store5

import com.dropbox.external.store5.fake.BadTestMarket
import com.dropbox.external.store5.fake.FakeDb
import com.dropbox.external.store5.fake.FakeFactory
import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.api.FakeApi
import com.dropbox.external.store5.fake.model.Note
import com.dropbox.external.store5.impl.ShareableLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class BadStoreTests {
    private val testScope = TestScope()
    private lateinit var api: FakeApi
    private lateinit var market: Market<String>
    private lateinit var db: FakeDb
    private lateinit var memoryLruCache: ShareableLruCache
    private lateinit var factory: FakeFactory<String, Note, Note>

    @BeforeTest
    fun before() {
        api = FakeApi()
        market = BadTestMarket.build()
        db = BadTestMarket.db
        memoryLruCache = BadTestMarket.memoryLruCache
        factory = FakeFactory(api)
    }

    @Test
    fun readFailureIsHandled() = testScope.runTest {
        val request = factory.buildReader<Note>(
            FakeNotes.One.key,
            refresh = true,
            fail = true,
            onCompletionsProducer = { listOf() })
        val response = market.read(request)
        advanceUntilIdle()
        val replayCache = response.replayCache
        assertContains(replayCache, MarketResponse.Loading)
        val last = replayCache.last()
        assertIs<MarketResponse.Failure>(last)
    }

    @Test
    fun deleteFailureIsHandled() = testScope.runTest {
        val response = market.delete(FakeNotes.One.key)
        assertEquals(false, response)
    }

    @Test
    fun clearFailureIsHandled() = testScope.runTest {
        val response = market.delete()
        assertEquals(false, response)
    }
}