package org.mobilenativefoundation.store.store5.market

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.market.data.fake.FakeApi
import org.mobilenativefoundation.store.store5.market.data.fake.FakeDatabase
import org.mobilenativefoundation.store.store5.market.data.fake.FakeMarket
import org.mobilenativefoundation.store.store5.market.data.fake.FakeNotes
import org.mobilenativefoundation.store.store5.market.data.market
import org.mobilenativefoundation.store.store5.market.data.model.Note
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class StoreFailureTests {
    private val testScope = TestScope()
    private lateinit var api: FakeApi
    private lateinit var market: Market<String, Note, Note, Note>
    private lateinit var database: FakeDatabase<Note>

    @BeforeTest
    fun before() {
        api = FakeApi()
        market = market(failRead = true, failWrite = true)
        database = FakeMarket.Failure.database
    }

    @Test
    fun readFailureIsHandled() = testScope.runTest {
        val reader = ReadRequest.of<String, Note>(
            key = FakeNotes.One.key,
            onCompletions = listOf(),
            refresh = true
        )

        val flow = market.read(reader)
        advanceUntilIdle()
        val responses = flow.take(3).toList()
        assertContains(responses, MarketResponse.Loading)
        val last = responses.last()
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
