package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.MarketResponse.Companion.Origin
import org.mobilenativefoundation.store.store5.data.complexMarket
import org.mobilenativefoundation.store.store5.data.complexReadRequest
import org.mobilenativefoundation.store.store5.data.complexWriteRequest
import org.mobilenativefoundation.store.store5.data.fake.FakeComplexDatabase
import org.mobilenativefoundation.store.store5.data.fake.FakeComplexMarket
import org.mobilenativefoundation.store.store5.data.fake.FakeComplexNotes
import org.mobilenativefoundation.store.store5.data.model.MarketData
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ComplexMarketTests {
    private val testScope = TestScope()
    private lateinit var market: Market<NoteMarketKey, NoteMarketInput, NoteMarketOutput>
    private lateinit var database: FakeComplexDatabase
    private lateinit var memoryLruStore: Store<NoteMarketKey, NoteMarketInput, NoteMarketOutput>

    @BeforeTest
    fun before() {
        market = complexMarket()
        database = FakeComplexMarket.Success.database
        memoryLruStore = FakeComplexMarket.Success.memoryLruStore
        database.reset()
        FakeComplexMarket.Success.api.reset()
        FakeComplexMarket.Failure.api.reset()
    }

    @Test
    fun givenEmptyMarketWhenReadThenLastFromNetwork() = testScope.runTest {
        val reader = complexReadRequest(FakeComplexNotes.GetById.One.key)
        val flow = market.read(reader)

        val responses = flow.take(3).toList()

        val first = responses.first()
        assertIs<MarketResponse.Loading>(first)

        testScope.advanceUntilIdle()

        val last = responses.last()
        assertIs<MarketResponse.Success<NoteMarketOutput>>(last)
        assertEquals(FakeComplexNotes.GetById.One.note, last.value)
        assertEquals(Origin.Network, last.origin)
    }

    @Test
    fun givenNonEmptyMarketWhenReadThenLastFromStore() =
        testScope.runTest {
            val reader = complexReadRequest(FakeComplexNotes.GetById.One.key)
            market.read(reader)
            testScope.advanceUntilIdle()

            val flow = market.read(reader)
            testScope.advanceUntilIdle()
            val responses = flow.take(4).toList()

            val last = responses.last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(last)
            assertEquals(FakeComplexNotes.GetById.One.note, last.value)
            assertEquals(Origin.Store, last.origin)
        }

    @Test
    fun givenEmptyMarketWhenMultipleReadsThenMultipleSuccessesFromNetwork() =
        testScope.runTest {
            val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)
            val readerTwo = complexReadRequest(FakeComplexNotes.GetById.Two.key)

            val flowOne = market.read(readerOne)
            market.read(readerTwo)

            testScope.advanceUntilIdle()

            val responsesOne = flowOne.take(3).toList()
            val lastResponseOne = responsesOne.last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
            val expected: NoteMarketOutput = FakeComplexNotes.GetById.One.note
            val actual: NoteMarketOutput = lastResponseOne.value
            assertEquals(expected, actual)
            assertEquals(Origin.Network, lastResponseOne.origin)

            val responsesTwo = flowOne.take(3).toList()
            val lastResponseTwo = responsesTwo.last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseTwo)
            assertEquals(FakeComplexNotes.GetById.One.note, lastResponseTwo.value)
            assertEquals(Origin.Network, lastResponseTwo.origin)
        }

    @Test
    fun givenEmptyMarketWhenWriteThenEmitFailure() = testScope.runTest {
        val newNote = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
        val writer = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNote)))

        assertFails { market.write(writer) }
    }

    @Test
    fun givenNonEmptyMarketWhenWriteThenSuccessFromLocalWriteAndApiUpdated() =
        testScope.runTest {
            val reader = complexReadRequest(FakeComplexNotes.GetById.One.key)
            val flow = market.read(reader)
            advanceUntilIdle()

            val newNote = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
            val writer = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNote)))

            val writeResponse = market.write(writer)
            advanceUntilIdle()
            assertEquals(true, writeResponse)

            val responsesAfterWrite = flow.take(4).toList()
            val lastResponseAfterWrite = responsesAfterWrite.last()

            assertIs<MarketResponse.WriteSuccess>(lastResponseAfterWrite)
            assertEquals(Origin.LocalWrite, lastResponseAfterWrite.origin)

            val apiValue = FakeComplexMarket.Success.api.get(FakeComplexNotes.GetById.One.key)
            assertIs<NoteMarketInput>(apiValue)
            val apiValueData = apiValue.data
            assertIs<MarketData.Single<Note>>(apiValueData)
            assertEquals(newNote, apiValueData.item)
        }

    @Test
    fun givenNonEmptyMarketWhenMultipleWritesThenMultipleSuccessesFromLocalWriteAndApiUpdated() =
        testScope.runTest {
            val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)
            val flowOne = market.read(readerOne)
            advanceUntilIdle()

            val newNoteOne = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
            val writerOne = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNoteOne)))

            val writeResponseOne = market.write(writerOne)
            advanceUntilIdle()
            assertEquals(true, writeResponseOne)

            val readerTwo = complexReadRequest(FakeComplexNotes.GetById.Two.key)
            val flowTwo = market.read(readerTwo)
            advanceUntilIdle()

            val newNoteTwo = ((FakeComplexNotes.GetById.Two.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
            val writerTwo = complexWriteRequest(FakeComplexNotes.GetById.Two.key, NoteMarketInput(MarketData.Single(newNoteTwo)))

            val writeResponseTwo = market.write(writerTwo)
            advanceUntilIdle()
            assertEquals(true, writeResponseTwo)

            val responsesAfterWriteOne = flowOne.take(4).toList()
            val lastResponseAfterWriteOne= responsesAfterWriteOne.last()

            assertIs<MarketResponse.WriteSuccess>(lastResponseAfterWriteOne)
//            val lastResponseAfterWriteOneValue = lastResponseAfterWriteOne.value
//            assertIs<NoteMarketOutput.Read>(lastResponseAfterWriteOneValue)
//            val lastResponseAfterWriteOneValueData = lastResponseAfterWriteOneValue.data
//            assertIs<MarketData.Single<Note>>(lastResponseAfterWriteOneValueData)
//            assertEquals(newNoteOne, lastResponseAfterWriteOneValueData.item)
            assertEquals(Origin.LocalWrite, lastResponseAfterWriteOne.origin)
            val apiValueOne = FakeComplexMarket.Success.api.get(FakeComplexNotes.GetById.One.key)
            assertIs<NoteMarketInput>(apiValueOne)
            val apiValueDataOne = apiValueOne.data
            assertIs<MarketData.Single<Note>>(apiValueDataOne)
            assertEquals(newNoteOne, apiValueDataOne.item)

            val responsesAfterWriteTwo:List<MarketResponse> = flowTwo.take(4).toList()
            val lastResponseAfterWriteTwo = responsesAfterWriteTwo.last()

            assertIs<MarketResponse.WriteSuccess>(lastResponseAfterWriteTwo)
            assertEquals(Origin.LocalWrite, lastResponseAfterWriteTwo.origin)
            val apiValueTwo = FakeComplexMarket.Success.api.get(FakeComplexNotes.GetById.Two.key)
            assertIs<NoteMarketInput>(apiValueTwo)
            val apiValueTwoData = apiValueTwo.data
            assertIs<MarketData.Single<Note>>(apiValueTwoData)
            assertEquals(newNoteTwo, apiValueTwoData.item)
        }

    @Test
    fun givenNonEmptyMarketWhenMultipleWritesWithOnCompletionsThenCorrectOnCompletionsExecuted() =
        testScope.runTest {
            val marketOnCompletions = mutableMapOf<String, MutableMap<String, Int>>()
            val networkOnCompletions = mutableMapOf<String, MutableMap<String, Int>>()

            val successCount = "SUCCESS_COUNT"
            val failureCount = "FAILURE_COUNT"

            fun onMarketCompletion(id: String) = OnMarketCompletion<NoteMarketOutput>(
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

            fun onNetworkCompletion() = OnNetworkCompletion<NoteMarketInput>(
                onSuccess = { result ->
                    val output = result.value
                    require(output.data is MarketData.Single<Note>)
                    val counts = networkOnCompletions[output.data.item.id] ?: mutableMapOf()
                    val successesBefore = counts[successCount] ?: 0
                    val successesAfter = successesBefore + 1
                    counts[successCount] = successesAfter
                    networkOnCompletions[output.data.item.id] = counts
                },
                onFailure = { result ->
                    val counts = networkOnCompletions[result.error.toString()] ?: mutableMapOf()
                    val failuresBefore = counts[failureCount] ?: 0
                    val failuresAfter = failuresBefore + 1
                    counts[failureCount] = failuresAfter
                    networkOnCompletions[result.error.toString()] = counts
                }
            )

            val market = complexMarket(onNetworkCompletion = onNetworkCompletion())

            val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)
            market.read(readerOne)

            val newNoteOneA = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title - A")
            val newNoteOneB = ((FakeComplexNotes.GetById.One.note).data as MarketData.Single<Note>).item.copy(title = "New Title - B")

            val writerOneA = complexWriteRequest(
                FakeComplexNotes.GetById.One.key,
                NoteMarketInput(MarketData.Single(newNoteOneA)),
                onCompletions = listOf(onMarketCompletion(FakeComplexNotes.GetById.One.key.toString()))
            )

            val writerOneB = complexWriteRequest(
                FakeComplexNotes.GetById.One.key,
                NoteMarketInput(MarketData.Single(newNoteOneB)),
                onCompletions = listOf(onMarketCompletion(FakeComplexNotes.GetById.One.key.toString()))
            )

            val writeResponseOneA = market.write(writerOneA)
            val writeResponseOneB = market.write(writerOneB)

            assertEquals(true, writeResponseOneA)
            assertEquals(true, writeResponseOneB)
            assertEquals(2, marketOnCompletions[FakeComplexNotes.GetById.One.key.toString()]!![successCount])
            assertEquals(1, marketOnCompletions.size)
            assertEquals(null, marketOnCompletions[FakeComplexNotes.GetById.One.key.toString()]!![failureCount])
            assertEquals(2, networkOnCompletions[FakeComplexNotes.GetById.One.note.data.item.id]!![successCount])
            assertEquals(1, networkOnCompletions.size)
        }

    @Test
    fun givenNonEmptyMarketWhenDeleteThenEmpty() = testScope.runTest {
        val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)
        val readerTwo = complexReadRequest(FakeComplexNotes.GetById.Two.key)

        val flowOne = market.read(readerOne)
        val flowTwo = market.read(readerTwo)

        advanceUntilIdle()

        val lastResponseOne = flowOne.take(3).toList().last()
        val lastResponseTwo = flowTwo.take(3).toList().last()

        assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
        assertEquals(FakeComplexNotes.GetById.One.note, lastResponseOne.value)

        assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseTwo)
        assertEquals(FakeComplexNotes.GetById.Two.note, lastResponseTwo.value)

        market.delete(FakeComplexNotes.GetById.One.key)
        market.delete(FakeComplexNotes.GetById.Two.key)

        testScope.advanceUntilIdle()

        val lastResponseOneAfterDelete = flowOne.take(4).last()
        val lastResponseTwoAfterDelete = flowTwo.take(4).last()

        assertIs<MarketResponse.Empty>(lastResponseOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastResponseTwoAfterDelete)
    }

    @Test
    fun givenNonEmptyMarketWhenClearThenEmpty() = testScope.runTest {
        val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)
        val readerTwo = complexReadRequest(FakeComplexNotes.GetById.Two.key)

        val flowOne = market.read(readerOne)
        val flowTwo = market.read(readerTwo)

        testScope.advanceUntilIdle()

        val lastResponseOne = flowOne.take(3).toList().last()
        val lastResponseTwo = flowTwo.take(3).toList().last()

        assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
        assertEquals(FakeComplexNotes.GetById.One.note, lastResponseOne.value)

        assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseTwo)
        assertEquals(FakeComplexNotes.GetById.Two.note, lastResponseTwo.value)

        market.delete()

        testScope.advanceUntilIdle()

        val lastResponseOneAfterDelete = flowOne.take(4).last()
        val lastResponseTwoAfterDelete = flowTwo.take(4).last()

        assertIs<MarketResponse.Empty>(lastResponseOneAfterDelete)
        assertIs<MarketResponse.Empty>(lastResponseTwoAfterDelete)
    }

    @Test
    fun givenNonEmptyMarketWhenRefreshWithOnCompletionsThenCorrectOnCompletionsExecuted() =
        testScope.runTest {
            val completed = mutableMapOf<String, Boolean>()

            val onCompletion = OnMarketCompletion<NoteMarketOutput>(
                onSuccess = { response ->
                    val output = response.value
                    require(output is NoteMarketOutput.Read && output.data is MarketData.Single<Note>)
                    completed[output.data.item.id] = true
                },
                onFailure = {}
            )

            val readerOne = complexReadRequest(
                key = FakeComplexNotes.GetById.One.key,
                refresh = true,
                onCompletions = listOf(onCompletion)
            )

            val readerTwo = complexReadRequest(
                key = FakeComplexNotes.GetById.Two.key,
                refresh = true,
                onCompletions = listOf(onCompletion)
            )

            val flowOne = market.read(readerOne)
            testScope.advanceUntilIdle()

            val flowTwo = market.read(readerTwo)
            testScope.advanceUntilIdle()

            val lastResponseOne: MarketResponse = flowOne.take(3).last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
            assertEquals(FakeComplexNotes.GetById.One.note, lastResponseOne.value)
            assertEquals(Origin.Network, lastResponseOne.origin)

            val lastResponseTwo = flowTwo.take(3).last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseTwo)
            assertEquals(FakeComplexNotes.GetById.Two.note, lastResponseTwo.value)
            assertEquals(Origin.Network, lastResponseTwo.origin)

            assertEquals(true, completed[((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.id])
            assertEquals(true, completed[((FakeComplexNotes.GetById.Two.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.id])
        }

    @Test
    fun givenNonEmptyMarketWhenReadWithoutRefreshAfterDeleteThenEmpty() =
        testScope.runTest {
            val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key)

            val newNote = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
            val writerOne = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNote)))

            market.read(readerOne)
            market.write(writerOne)
            market.delete(FakeComplexNotes.GetById.One.key)

            val responsesOne = market.read(readerOne)
            testScope.advanceUntilIdle()
            val lastResponseOne = responsesOne.take(5).last()
            assertIs<MarketResponse.Empty>(lastResponseOne)
        }

    @Test
    fun givenNonEmptyMarketWhenReadWithRefreshAfterDeleteThenSuccessFromNetwork() =
        testScope.runTest {
            val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key, refresh = true)

            val newNoteOne = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
            val writerOne = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNoteOne)))

            market.read(readerOne)
            market.write(writerOne)
            market.delete(FakeComplexNotes.GetById.One.key)

            val responsesOne = market.read(readerOne)
            testScope.advanceUntilIdle()
            val lastResponseOne = responsesOne.take(7).last()
            assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
            val lastResponseOneValue = lastResponseOne.value
            assertIs<NoteMarketOutput.Read>(lastResponseOneValue)
            val lastResponseOneValueData = lastResponseOneValue.data
            assertIs<MarketData.Single<Note>>(lastResponseOneValueData)
            assertEquals(newNoteOne, lastResponseOneValueData.item)
        }

    @Test
    fun givenEmptyMarketWhenRefreshThenEagerlyResolve() = testScope.runTest {
        val market = complexMarket()

        val readerOne = complexReadRequest(FakeComplexNotes.GetById.One.key, refresh = true)
        val newNoteOne = ((FakeComplexNotes.GetById.One.note as NoteMarketOutput.Read).data as MarketData.Single<Note>).item.copy(title = "New Title")
        val writerOne = complexWriteRequest(FakeComplexNotes.GetById.One.key, NoteMarketInput(MarketData.Single(newNoteOne)))

        market.read(readerOne)
        market.write(writerOne)
        market.delete(FakeComplexNotes.GetById.One.key)

        val flowOne = market.read(readerOne)
        testScope.advanceUntilIdle()

        val lastResponsesOne :List<MarketResponse> = flowOne.take(7).toList()
        val lastResponseOne = flowOne.take(7).last()
        assertIs<MarketResponse.Success<NoteMarketOutput>>(lastResponseOne)
        val lastResponseOneValue = lastResponseOne.value
        assertIs<NoteMarketOutput.Read>(lastResponseOneValue)
        val lastResponseOneValueData = lastResponseOneValue.data
        assertIs<MarketData.Single<Note>>(lastResponseOneValueData)
        assertEquals(newNoteOne, lastResponseOneValueData.item)
        assertEquals(MarketResponse.Companion.Origin.Network, lastResponseOne.origin)
        //Todo fix assert
//        assertContains(
//            lastResponsesOne,
//            MarketResponse.Success<NoteMarketOutput>(NoteMarketOutput.Read(MarketData.Single(newNoteOne)), Origin.LocalWrite)
//        )

        assertContains(
            lastResponsesOne,
            MarketResponse.Empty
        )
    }
}
