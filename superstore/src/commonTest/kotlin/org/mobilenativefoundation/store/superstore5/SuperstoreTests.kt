package org.mobilenativefoundation.store.superstore5

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.superstore5.util.fake.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SuperstoreTests {
    private val testScope = TestScope()
    private lateinit var api: PrimaryPagesApi
    private lateinit var secondaryApi: Warehouse<String, Page>
    private lateinit var hardcodedPages: Warehouse<String, Page.Data>
    private lateinit var pagesDatabase: PagesDatabase

    @BeforeTest
    fun before() {
        api = PrimaryPagesApi()
        secondaryApi = SecondaryPagesApi()
        hardcodedPages = HardcodedPages()
        pagesDatabase = PagesDatabase()
    }

    @Test
    fun givenEmptyStoreWhenSuccessFromPrimaryApiThenSuperstoreResponseOfPrimaryApiResult() = testScope.runTest {
        val ttl = null
        val fail = false
        val store = StoreBuilder.from<String, Page, Page>(
            fetcher = Fetcher.of { key -> api.fetch(key, fail, ttl) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> pagesDatabase.get(key) },
                writer = { key, page -> pagesDatabase.put(key, page) },
                delete = null,
                deleteAll = null
            )
        ).build()
        val superstore = Superstore.from(
            store = store,
            warehouses = listOf(secondaryApi, hardcodedPages)
        )

        val responses = superstore.get("1").take(2).toList()

        assertEquals(
            listOf(
                SuperstoreResponse.Loading,
                SuperstoreResponse.Data(Page.Data("1", null), SuperstoreResponseOrigin.Fetcher)
            ), responses
        )
    }

    @Test
    fun givenEmptyStoreWhenFailureFromPrimaryApiThenSuperstoreResponseOfSecondaryApiResult() = testScope.runTest {
        val ttl = null
        val fail = true
        val store = StoreBuilder.from<String, Page, Page>(
            fetcher = Fetcher.of { key -> api.fetch(key, fail, ttl) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> pagesDatabase.get(key) },
                writer = { key, page -> pagesDatabase.put(key, page) },
                delete = null,
                deleteAll = null
            )
        ).build()
        val superstore = Superstore.from(
            store = store,
            warehouses = listOf(secondaryApi, hardcodedPages)
        )

        val responses = superstore.get("1").take(2).toList()

        assertEquals(
            listOf(
                SuperstoreResponse.Loading,
                SuperstoreResponse.Data(Page.Data("1", null), SuperstoreResponseOrigin.Warehouse)
            ), responses
        )
    }


    @Test
    fun givenEmptyStoreWhenFailureFromPrimaryAndSecondaryApisThenSuperstoreResponseOfHardcodedData() =
        testScope.runTest {
            val ttl = null
            val fail = true

            val brokenSecondaryApi = object : Warehouse<String, Page> {
                override suspend fun get(key: String): Page = throw Exception()

            }

            val store = StoreBuilder.from<String, Page, Page>(
                fetcher = Fetcher.of { key -> api.fetch(key, fail, ttl) },
                sourceOfTruth = SourceOfTruth.of(
                    nonFlowReader = { key -> pagesDatabase.get(key) },
                    writer = { key, page -> pagesDatabase.put(key, page) },
                    delete = null,
                    deleteAll = null
                )
            ).build()
            val superstore = Superstore.from(
                store = store,
                warehouses = listOf(brokenSecondaryApi, hardcodedPages)
            )

            val responses = superstore.get("1").take(2).toList()

            assertEquals(
                listOf(
                    SuperstoreResponse.Loading,
                    SuperstoreResponse.Data(Page.Data("One", null), SuperstoreResponseOrigin.Warehouse)
                ), responses
            )
        }
}