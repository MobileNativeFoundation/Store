package com.dropbox.external.store5

import com.dropbox.external.store5.data.fake.FakeDatabase
import com.dropbox.external.store5.data.fake.FakeMarket
import com.dropbox.external.store5.data.fake.FakeNotes
import com.dropbox.external.store5.data.market
import com.dropbox.external.store5.data.marketReader
import com.dropbox.external.store5.data.marketWriter
import com.dropbox.external.store5.data.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
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
    private lateinit var database: FakeDatabase
    private lateinit var memoryLruCache: MemoryLruCache

    @BeforeTest
    fun before() {
        database = FakeMarket.Success.database
        memoryLruCache = FakeMarket.Success.memoryLruCache
        database.reset()
    }

    @Test
    fun `GIVEN non-empty offline market WHEN write THEN success from local write`() =
        testScope.runTest {
            val market = market(failWrite = true)
            val readerOne = marketReader(FakeNotes.One.key)
            val flowOne = market.read(readerOne)
            testScope.advanceUntilIdle()

            val newNote = FakeNotes.One.note.copy(title = "New Title")
            val writerOne = marketWriter(FakeNotes.One.key, newNote)

            val writeResponseOne = market.write(writerOne)
            assertEquals(false, writeResponseOne)
            testScope.advanceUntilIdle()

            val lastResponseOne = flowOne.take(4).last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(newNote, lastResponseOne.value)
            assertEquals(MarketResponse.Companion.Origin.LocalWrite, lastResponseOne.origin)
        }

    @Test
    fun `GIVEN non-empty offline market WHEN refresh with on-completions THEN correct on-completions executed`() =
        testScope.runTest {
            val market = market(
                failRead = true,
                failWrite = true
            )

            var readErrorsHandled = 0

            val onCompletion = OnMarketCompletion<Note>(
                onSuccess = {},
                onFailure = { readErrorsHandled++ }
            )

            val readerOne = marketReader(
                key = FakeNotes.One.key,
                refresh = true,
                onCompletions = listOf(onCompletion)
            )
            val flowOne = market.read(readerOne)
            val firstResponseOne = flowOne.first()
            assertIs<MarketResponse.Loading>(firstResponseOne)

            testScope.advanceUntilIdle()

            val secondResponseOne = flowOne.take(3).last()
            assertIs<MarketResponse.Failure>(secondResponseOne)
            assertEquals(MarketResponse.Companion.Origin.Network, secondResponseOne.origin)

            assertEquals(1, readErrorsHandled)
        }
}
