package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.fake.fallback.HardcodedPages
import org.mobilenativefoundation.store.store5.util.fake.fallback.Page
import org.mobilenativefoundation.store.store5.util.fake.fallback.PagesDatabase
import org.mobilenativefoundation.store.store5.util.fake.fallback.PrimaryPagesApi
import org.mobilenativefoundation.store.store5.util.fake.fallback.SecondaryPagesApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceOfTruthFallbackTests {
    private val testScope = TestScope()
    private lateinit var api: PrimaryPagesApi
    private lateinit var secondaryApi: SecondaryPagesApi
    private lateinit var hardcodedPages: HardcodedPages
    private lateinit var pagesDatabase: PagesDatabase

    @BeforeTest
    fun before() {
        api = PrimaryPagesApi()
        secondaryApi = SecondaryPagesApi()
        hardcodedPages = HardcodedPages()
        pagesDatabase = PagesDatabase()
    }

    @Test
    fun givenNonEmptyStoreAndSourceOfTruthAsFallbackWhenFailureFromPrimaryApiThenStoreReadResponseOfSourceOfTruthResult() =
        testScope.runTest {

            val sourceOfTruth = SourceOfTruth.of<String, Page>(
                nonFlowReader = { key -> pagesDatabase.get(key) },
                writer = { key, page -> pagesDatabase.put(key, page) },
                delete = null,
                deleteAll = null
            )

            val ttl = null
            var fail = false
            val store = StoreBuilder.from<String, Page, Page>(
                fetcher = Fetcher.of { key -> api.fetch(key, fail, ttl) },
                sourceOfTruth = sourceOfTruth
            ).build()

            val responsesWithEmptyStore = store.stream(StoreReadRequest.fresh("1")).take(2).toList()

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data(Page.Data("1", null), StoreReadResponseOrigin.Fetcher())
                ),
                responsesWithEmptyStore
            )
            fail = true
            val responsesWithNonEmptyStore =
                store.stream(StoreReadRequest.freshWithFallBackToSourceOfTruth("1")).take(2).toList()
            assertEquals(
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data(Page.Data("1", null), StoreReadResponseOrigin.SourceOfTruth)
                ),
                responsesWithNonEmptyStore
            )
        }
}
