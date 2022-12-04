package com.dropbox.external.store5

import com.dropbox.external.store5.data.fake.FakeDatabase
import com.dropbox.external.store5.data.fake.FakeMarket
import com.dropbox.external.store5.data.fake.FakeNotes
import com.dropbox.external.store5.data.market
import com.dropbox.external.store5.data.model.Note
import com.dropbox.external.store5.data.readRequestWithValidator
import com.dropbox.external.store5.impl.MemoryLruStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ItemValidatorTests {
    private val testScope = TestScope()
    private lateinit var market: Market<String, Note, Note>
    private lateinit var database: FakeDatabase<Note>
    private lateinit var memoryLruStore: MemoryLruStore<Note>

    @BeforeTest
    fun before() {
        market = market()
        database = FakeMarket.Success.database
        memoryLruStore = FakeMarket.Success.memoryLruStore
        database.reset()
        FakeMarket.Success.api.reset()
        FakeMarket.Failure.api.reset()
    }

    @Test
    fun givenValidatorAndEmptyMarketWhenReadThenSuccessOriginatingFromNetwork() =
        testScope.runTest {
            val readerOne = readRequestWithValidator(FakeNotes.One.key, isValid = true)
            val flowOne = market.read(readerOne)
            val responsesOne = flowOne.take(3).toList()

            testScope.advanceUntilIdle()

            val lastResponseOne = responsesOne.last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(FakeNotes.One.note, lastResponseOne.value)
            assertEquals(MarketResponse.Companion.Origin.Network, lastResponseOne.origin)
        }

    @Test
    fun givenValidatorAndNonEmptyMarketWithValidGoodWhenReadThenSuccessOriginatingFromStore() =
        testScope.runTest {
            val readerOne = readRequestWithValidator(FakeNotes.One.key)
            market.read(readerOne)
            testScope.advanceUntilIdle()

            testScope.advanceUntilIdle()
            val readerTwo = readRequestWithValidator(FakeNotes.One.key, isValid = true)
            val flowTwo = market.read(readerTwo)
            val responsesTwo = flowTwo.take(4).toList()

            testScope.advanceUntilIdle()

            val lastResponseTwo = responsesTwo.last()
            assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
            assertEquals(FakeNotes.One.note, lastResponseTwo.value)
            assertEquals(MarketResponse.Companion.Origin.Store, lastResponseTwo.origin)
        }

    @Test
    fun givenValidatorAndNonEmptyMarketWithInvalidGoodWhenReadThenSuccessOriginatingFromNetwork() =
        testScope.runTest {
            val readerOne = readRequestWithValidator(FakeNotes.One.key)
            market.read(readerOne)
            testScope.advanceUntilIdle()

            testScope.advanceUntilIdle()
            val readerTwo = readRequestWithValidator(FakeNotes.One.key, isValid = false)
            val flowTwo = market.read(readerTwo)
            val responsesTwo = flowTwo.take(4).toList()

            testScope.advanceUntilIdle()

            val lastResponseTwo = responsesTwo.last()
            assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
            assertEquals(FakeNotes.One.note, lastResponseTwo.value)
            assertEquals(MarketResponse.Companion.Origin.Network, lastResponseTwo.origin)
        }
}
