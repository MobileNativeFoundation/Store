package com.dropbox.external.store5

import com.dropbox.external.store5.MarketResponse.Companion.Origin
import com.dropbox.external.store5.data.fake.FakeDatabase
import com.dropbox.external.store5.data.fake.FakeMarket
import com.dropbox.external.store5.data.fake.FakeNotes
import com.dropbox.external.store5.data.market
import com.dropbox.external.store5.data.marketReader
import com.dropbox.external.store5.data.marketWriter
import com.dropbox.external.store5.data.model.Note
import com.dropbox.external.store5.impl.MemoryLruCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MarketTests {
    private val testScope = TestScope()
    private lateinit var market: Market<String, Note, Note>
    private lateinit var database: FakeDatabase
    private lateinit var memoryLruCache: MemoryLruCache

    @BeforeTest
    fun before() {
        market = market()
        database = FakeMarket.Success.database
        memoryLruCache = FakeMarket.Success.memoryLruCache
        database.reset()
    }

    @Test
    fun `GIVEN empty market WHEN read THEN last from network`() = testScope.runTest {
        val reader = marketReader(FakeNotes.One.key)
        val flow = market.read(reader)

        val responses = flow.take(3).toList()

        val first = responses.first()
        assertIs<MarketResponse.Loading>(first)

        testScope.advanceUntilIdle()

        val last = responses.last()
        assertIs<MarketResponse.Success<Note>>(last)
        assertEquals(FakeNotes.One.note, last.value)
        assertEquals(Origin.Network, last.origin)
    }

    @Test
    fun `GIVEN non-empty market WHEN read THEN last from store`() =
        testScope.runTest {
            val reader = marketReader(FakeNotes.One.key)
            market.read(reader)
            testScope.advanceUntilIdle()

            val flow = market.read(reader)
            testScope.advanceUntilIdle()
            val responses = flow.take(4).toList()

            val last = responses.last()
            assertIs<MarketResponse.Success<Note>>(last)
            assertEquals(FakeNotes.One.note, last.value)
            assertEquals(Origin.Store, last.origin)
        }

    @Test
    fun `GIVEN empty market WHEN multiple reads THEN multiple successes from network`() =
        testScope.runTest {
            val readerOne = marketReader(FakeNotes.One.key)
            val readerTwo = marketReader(FakeNotes.Two.key)

            val flowOne = market.read(readerOne)
            market.read(readerTwo)

            testScope.advanceUntilIdle()

            val responsesOne = flowOne.take(3).toList()
            val lastResponseOne = responsesOne.last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(FakeNotes.One.note, lastResponseOne.value)
            assertEquals(Origin.Network, lastResponseOne.origin)

            val responsesTwo = flowOne.take(3).toList()
            val lastResponseTwo = responsesTwo.last()
            assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
            assertEquals(FakeNotes.One.note, lastResponseTwo.value)
            assertEquals(Origin.Network, lastResponseTwo.origin)
        }

    @Test
    fun `GIVEN empty market WHEN write THEN emit failure`() = testScope.runTest {
        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writer = marketWriter(FakeNotes.One.key, newNote)

        assertFails { market.write(writer) }
    }

    @Test
    fun `GIVEN non-empty market WHEN write THEN success from local write and API updated`() =
        testScope.runTest {
            val reader = marketReader(FakeNotes.One.key)
            val flow = market.read(reader)
            advanceUntilIdle()

            val newNote = FakeNotes.One.note.copy(title = "New Title")
            val writer = marketWriter(FakeNotes.One.key, newNote)
            val writeResponse = market.write(writer)
            advanceUntilIdle()
            assertEquals(true, writeResponse)

            val responsesAfterWrite = flow.take(4).toList()
            val lastResponseAfterWrite = responsesAfterWrite.last()

            assertIs<MarketResponse.Success<Note>>(lastResponseAfterWrite)
            assertEquals(newNote, lastResponseAfterWrite.value)
            assertEquals(Origin.LocalWrite, lastResponseAfterWrite.origin)
            assertEquals(newNote, FakeMarket.Success.api.get(FakeNotes.One.key))
        }

    @Test
    fun `GIVEN non-empty market WHEN multiple writes THEN multiple successes from local write and API updated`() =
        testScope.runTest {
            val readerOne = marketReader(FakeNotes.One.key)
            val flowOne = market.read(readerOne)
            advanceUntilIdle()

            val newNoteOne = FakeNotes.One.note.copy(title = "New Title")
            val writerOne = marketWriter(FakeNotes.One.key, newNoteOne)
            val writeResponseOne = market.write(writerOne)
            advanceUntilIdle()
            assertEquals(true, writeResponseOne)

            val readerTwo = marketReader(FakeNotes.Two.key)
            val flowTwo = market.read(readerTwo)
            advanceUntilIdle()

            val newNoteTwo = FakeNotes.Two.note.copy(title = "New Title")
            val writerTwo = marketWriter(FakeNotes.Two.key, newNoteTwo)
            val writeResponseTwo = market.write(writerTwo)
            advanceUntilIdle()
            assertEquals(true, writeResponseTwo)

            val responsesAfterWriteOne = flowOne.take(4).toList()
            val lastResponseAfterWriteOne = responsesAfterWriteOne.last()

            assertIs<MarketResponse.Success<Note>>(lastResponseAfterWriteOne)
            assertEquals(newNoteOne, lastResponseAfterWriteOne.value)
            assertEquals(Origin.LocalWrite, lastResponseAfterWriteOne.origin)
            assertEquals(newNoteOne, FakeMarket.Success.api.get(FakeNotes.One.key))

            val responsesAfterWriteTwo = flowTwo.take(4).toList()
            val lastResponseAfterWriteTwo = responsesAfterWriteTwo.last()

            assertIs<MarketResponse.Success<Note>>(lastResponseAfterWriteTwo)
            assertEquals(newNoteTwo, lastResponseAfterWriteTwo.value)
            assertEquals(Origin.LocalWrite, lastResponseAfterWriteTwo.origin)
            assertEquals(newNoteTwo, FakeMarket.Success.api.get(FakeNotes.Two.key))
        }

    @Test
    fun `GIVEN non-empty market WHEN multiple writes with on-completions THEN correct on-completions executed`() =
        testScope.runTest {
            val marketOnCompletions = mutableMapOf<String, MutableMap<String, Int>>()
            val networkOnCompletions = mutableMapOf<String, MutableMap<String, Int>>()

            val successCount = "SUCCESS_COUNT"
            val failureCount = "FAILURE_COUNT"

            fun onMarketCompletion(id: String) = OnMarketCompletion<Note>(
                onSuccess = {
                    val counts = marketOnCompletions[id] ?: mutableMapOf()
                    val successesBefore = counts[successCount] ?: 0
                    val successesAfter = successesBefore + 1
                    counts[successCount] = successesAfter
                    marketOnCompletions[id] = counts
                },
                onFailure = {
                    val counts = marketOnCompletions[id] ?: mutableMapOf()
                    val failuresBefore = counts[failureCount] ?: 0
                    val failuresAfter = failuresBefore + 1
                    counts[failureCount] = failuresAfter
                    marketOnCompletions[id] = counts
                }
            )

            fun onNetworkCompletion() = OnNetworkCompletion<Note>(
                onSuccess = { result ->
                    val counts = networkOnCompletions[result.value.id] ?: mutableMapOf()
                    val successesBefore = counts[successCount] ?: 0
                    val successesAfter = successesBefore + 1
                    counts[successCount] = successesAfter
                    networkOnCompletions[result.value.id] = counts
                },
                onFailure = { result ->
                    val counts = networkOnCompletions[result.error.toString()] ?: mutableMapOf()
                    val failuresBefore = counts[failureCount] ?: 0
                    val failuresAfter = failuresBefore + 1
                    counts[failureCount] = failuresAfter
                    networkOnCompletions[result.error.toString()] = counts
                }
            )

            val market = market(onNetworkCompletion = onNetworkCompletion())

            val readerOne = marketReader(FakeNotes.One.key)
            market.read(readerOne)

            val newNoteOneA = FakeNotes.One.note.copy(title = "New Title - A")
            val newNoteOneB = FakeNotes.One.note.copy(title = "New Title - B")
            val writerOneA = marketWriter(
                FakeNotes.One.key,
                newNoteOneA,
                onCompletions = listOf(onMarketCompletion(FakeNotes.One.key))
            )

            val writerOneB = marketWriter(
                FakeNotes.One.key,
                newNoteOneB,
                onCompletions = listOf(onMarketCompletion(FakeNotes.One.key))
            )

            val writeResponseOneA = market.write(writerOneA)
            val writeResponseOneB = market.write(writerOneB)

            assertEquals(true, writeResponseOneA)
            assertEquals(true, writeResponseOneB)
            assertEquals(2, marketOnCompletions[FakeNotes.One.key]!![successCount])
            assertEquals(1, marketOnCompletions.size)
            assertEquals(null, marketOnCompletions[FakeNotes.One.key]!![failureCount])
            assertEquals(2, networkOnCompletions[FakeNotes.One.note.id]!![successCount])
            assertEquals(1, networkOnCompletions.size)
        }

    @Test
    fun `GIVEN non-empty market WHEN delete THEN empty`() = testScope.runTest {
        val readerOne = marketReader(FakeNotes.One.key)
        val readerTwo = marketReader(FakeNotes.Two.key)

        val flowOne = market.read(readerOne)
        val flowTwo = market.read(readerTwo)

        testScope.advanceUntilIdle()

        val lastResponseOne = flowOne.take(3).toList().last()
        val lastResponseTwo = flowTwo.take(3).toList().last()

        assertIs<MarketResponse.Success<Note>>(lastResponseOne)
        assertEquals(FakeNotes.One.note, lastResponseOne.value)

        assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
        assertEquals(FakeNotes.Two.note, lastResponseTwo.value)

        market.delete(FakeNotes.One.key)
        market.delete(FakeNotes.Two.key)

        testScope.advanceUntilIdle()

        val lastResponseOneAfterDelete = flowOne.take(4).last()
        val lastResponseTwoAfterDelete = flowTwo.take(4).last()

        assertIs<MarketResponse.Empty>(lastResponseOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastResponseTwoAfterDelete)
    }

    @Test
    fun `GIVEN non-empty market WHEN clear THEN empty`() = testScope.runTest {
        val readerOne = marketReader(FakeNotes.One.key)
        val readerTwo = marketReader(FakeNotes.Two.key)

        val flowOne = market.read(readerOne)
        val flowTwo = market.read(readerTwo)

        testScope.advanceUntilIdle()

        val lastResponseOne = flowOne.take(3).toList().last()
        val lastResponseTwo = flowTwo.take(3).toList().last()

        assertIs<MarketResponse.Success<Note>>(lastResponseOne)
        assertEquals(FakeNotes.One.note, lastResponseOne.value)

        assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
        assertEquals(FakeNotes.Two.note, lastResponseTwo.value)

        market.delete()

        testScope.advanceUntilIdle()

        val lastResponseOneAfterDelete = flowOne.take(4).last()
        val lastResponseTwoAfterDelete = flowTwo.take(4).last()

        assertIs<MarketResponse.Empty>(lastResponseOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastResponseTwoAfterDelete)
    }

    @Test
    fun `GIVEN non-empty market WHEN refresh with on-completions THEN correct on-completions executed`() =
        testScope.runTest {
            val completed = mutableMapOf<String, Boolean>()

            val onCompletion = OnMarketCompletion<Note>(
                onSuccess = { response -> completed[response.value.id] = true },
                onFailure = {}
            )

            val readerOne = marketReader(
                key = FakeNotes.One.key,
                refresh = true,
                onCompletions = listOf(onCompletion)
            )

            val readerTwo = marketReader(
                key = FakeNotes.Two.key,
                refresh = true,
                onCompletions = listOf(onCompletion)
            )

            val flowOne = market.read(readerOne)
            testScope.advanceUntilIdle()

            val flowTwo = market.read(readerTwo)
            testScope.advanceUntilIdle()

            val lastResponseOne = flowOne.take(3).last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(FakeNotes.One.note, lastResponseOne.value)
            assertEquals(Origin.Network, lastResponseOne.origin)

            val lastResponseTwo = flowTwo.take(3).last()
            assertIs<MarketResponse.Success<Note>>(lastResponseTwo)
            assertEquals(FakeNotes.Two.note, lastResponseTwo.value)
            assertEquals(Origin.Network, lastResponseTwo.origin)

            assertEquals(true, completed[FakeNotes.One.note.id])
            assertEquals(true, completed[FakeNotes.Two.note.id])
        }

    @Test
    fun `GIVEN non-empty market WHEN read without refresh after delete THEN empty`() =
        testScope.runTest {
            val readerOne = marketReader(FakeNotes.One.key)
            val newNote = FakeNotes.One.note.copy(title = "New Title")
            val writerOne = marketWriter(FakeNotes.One.key, newNote)

            market.read(readerOne)
            market.write(writerOne)
            market.delete(FakeNotes.One.key)

            val responsesOne = market.read(readerOne)
            testScope.advanceUntilIdle()
            val lastResponseOne = responsesOne.take(5).last()
            assertIs<MarketResponse.Empty>(lastResponseOne)
        }

    @Test
    fun `GIVEN non-empty market WHEN read with refresh after delete THEN success from network`() =
        testScope.runTest {
            val readerOne = marketReader(FakeNotes.One.key, refresh = true)
            val newNote = FakeNotes.One.note.copy(title = "New Title")
            val writerOne = marketWriter(FakeNotes.One.key, newNote)

            market.read(readerOne)
            market.write(writerOne)
            market.delete(FakeNotes.One.key)

            val responsesOne = market.read(readerOne)
            testScope.advanceUntilIdle()
            val lastResponseOne = responsesOne.take(7).last()
            assertIs<MarketResponse.Success<Note>>(lastResponseOne)
            assertEquals(newNote, lastResponseOne.value)
        }

    @Test
    fun `GIVEN empty market WHEN refresh THEN eagerly resolve`() = testScope.runTest {
        val market = market()

        val readerOne = marketReader(FakeNotes.One.key, refresh = true)
        val newNote = FakeNotes.One.note.copy(title = "New Title")
        val writerOne = marketWriter(FakeNotes.One.key, newNote)

        market.read(readerOne)
        market.write(writerOne)
        market.delete(FakeNotes.One.key)

        val flowOne = market.read(readerOne)
        testScope.advanceUntilIdle()
        val lastResponsesOne = flowOne.take(7).toList()
        val lastResponseOne = flowOne.take(7).last()
        assertIs<MarketResponse.Success<Note>>(lastResponseOne)
        assertEquals(newNote, lastResponseOne.value)
        assertEquals(MarketResponse.Companion.Origin.Network, lastResponseOne.origin)

        assertContains(
            lastResponsesOne,
            MarketResponse.Success(newNote, Origin.LocalWrite)
        )

        assertContains(
            lastResponsesOne,
            MarketResponse.Empty
        )
    }
}
