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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class GoodValidatorTests {
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
    fun `GIVEN validator and empty Market WHEN read THEN success originating from network`() = testScope.runTest {
        val request = factory.buildReaderWithValidator<Note>(FakeNotes.One.key, isValid = true)
        val response = async { market.read(request) }
        val flow = response.await()
        val responses = flow.take(3).toList()

        testScope.advanceUntilIdle()

        val last = responses.last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)
        assertEquals(MarketResponse.Companion.Origin.Network, last.origin)
    }

    @Test
    fun `GIVEN validator and non-empty Market with valid good WHEN read THEN success originating from Store`() =
        testScope.runTest {
            val requestOne = factory.buildReaderWithValidator<Note>(FakeNotes.One.key)
            market.read(requestOne)
            testScope.advanceUntilIdle()

            testScope.advanceUntilIdle()
            val requestTwo = factory.buildReaderWithValidator<Note>(FakeNotes.One.key, isValid = true)
            val responseTwo = async { market.read(requestTwo) }
            val flowTwo = responseTwo.await()
            val responsesTwo = flowTwo.take(4).toList()

            testScope.advanceUntilIdle()

            val last = responsesTwo.last()
            assertIs<MarketResponse.Success<Note>>(last)
            assertEquals(FakeNotes.One.note, last.value)
            assertEquals(MarketResponse.Companion.Origin.Store, last.origin)
        }

    @Test
    fun `GIVEN validator and non-empty Market with invalid good WHEN read THEN success originating from network`() =
        testScope.runTest {
            val requestOne = factory.buildReaderWithValidator<Note>(FakeNotes.One.key)
            market.read(requestOne)
            testScope.advanceUntilIdle()

            testScope.advanceUntilIdle()
            val requestTwo = factory.buildReaderWithValidator<Note>(FakeNotes.One.key, isValid = false)
            val responseTwo = async { market.read(requestTwo) }
            val flowTwo = responseTwo.await()
            val responsesTwo = flowTwo.take(4).toList()

            testScope.advanceUntilIdle()

            val last = responsesTwo.last()
            assertIs<MarketResponse.Success<Note>>(last)
            assertEquals(FakeNotes.One.note, last.value)
            assertEquals(MarketResponse.Companion.Origin.Network, last.origin)
        }
}
