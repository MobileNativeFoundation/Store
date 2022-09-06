package com.dropbox.store

import com.dropbox.store.fake.BadTestMarket
import com.dropbox.store.fake.FakeDb
import com.dropbox.store.fake.FakeFactory
import com.dropbox.store.fake.FakeNotes
import com.dropbox.store.fake.api.FakeApi
import com.dropbox.store.fake.model.Note
import com.dropbox.store.impl.ShareableLruCache
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
        market = BadTestMarket.build(testScope)
        db = BadTestMarket.db
        memoryLruCache = BadTestMarket.memoryLruCache
        factory = FakeFactory(api)
    }

    @Test
    fun readFailureIsHandled() = testScope.runTest {
        val request = factory.buildReader<Note>(FakeNotes.One.key, refresh = true, fail = true)
        val response = market.read(request)
        advanceUntilIdle()
        val replayCache = response.replayCache
        assertContains(replayCache, Market.Response.Loading)
        val last = replayCache.last()
        assertIs<Market.Response.Failure>(last)
    }

    @Test
    fun deleteFailureIsHandled() = testScope.runTest {
        val response = market.delete(FakeNotes.One.key)
        assertEquals(false, response)
    }

    @Test
    fun clearFailureIsHandled() = testScope.runTest {
        val response = market.clear()
        assertEquals(false, response)
    }
}