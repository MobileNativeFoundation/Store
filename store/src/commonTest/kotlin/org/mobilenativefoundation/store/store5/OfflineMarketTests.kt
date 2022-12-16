package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.data.fake.FakeDatabase
import org.mobilenativefoundation.store.store5.data.fake.FakeMarket
import org.mobilenativefoundation.store.store5.data.fake.FakeNotes
import org.mobilenativefoundation.store.store5.data.market
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.readRequest
import org.mobilenativefoundation.store.store5.data.writeRequest
import org.mobilenativefoundation.store.store5.impl.MemoryLruStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMarketTests {
    private val testScope = TestScope()
    private lateinit var database: FakeDatabase<Note>
    private lateinit var memoryLruStore: MemoryLruStore<Note>

    @BeforeTest
    fun before() {
        database = FakeMarket.Success.database
        memoryLruStore = FakeMarket.Success.memoryLruStore
        database.reset()
    }

    @Test
    fun givenNonEmptyOfflineMarketWhenWriteThenSuccessFromLocalWrite() =
        testScope.runTest {
            val market = market(failWrite = true)
            val readerOne = readRequest(FakeNotes.One.key)
            val flowOne = market.read(readerOne)
            testScope.advanceUntilIdle()

            val newNote = FakeNotes.One.note.copy(title = "New Title")
            val writerOne = writeRequest(FakeNotes.One.key, newNote)
            assertFails { market.write(writerOne) }
            testScope.advanceUntilIdle()

            val lastResponseOne = flowOne.take(4).last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(newNote, lastResponseOne.value)
            assertEquals(MarketResponse.Companion.Origin.LocalWrite, lastResponseOne.origin)
        }

    @Test
    fun givenNonEmptyOfflineMarketWhenRefreshWithOnCompletionsThenCorrectOnCompletionsExecuted() =
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

            val readerOne = readRequest(
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
