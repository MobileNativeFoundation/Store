package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.test_utils.fake.fallback.HardcodedPages
import org.mobilenativefoundation.store.store5.test_utils.fake.fallback.Page
import org.mobilenativefoundation.store.store5.test_utils.fake.fallback.PagesDatabase
import org.mobilenativefoundation.store.store5.test_utils.fake.fallback.PrimaryPagesApi
import org.mobilenativefoundation.store.store5.test_utils.fake.fallback.SecondaryPagesApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FallbackTests {
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
    fun givenEmptyStoreWhenSuccessFromPrimaryApiThenStoreReadResponseOfPrimaryApiResult() =
        testScope.runTest {
            val ttl = null
            val fail = false

            val hardcodedPagesFetcher = Fetcher.of<String, Page> { key -> hardcodedPages.get(key) }
            val secondaryApiFetcher =
                Fetcher.withFallback(
                    secondaryApi.name,
                    hardcodedPagesFetcher,
                ) { key -> secondaryApi.get(key) }

            val store =
                StoreBuilder.from(
                    fetcher = Fetcher.withFallback(api.name, secondaryApiFetcher) { key -> api.fetch(key, fail, ttl) },
                    sourceOfTruth =
                        SourceOfTruth.of(
                            nonFlowReader = { key -> pagesDatabase.get(key) },
                            writer = { key, page -> pagesDatabase.put(key, page) },
                            delete = null,
                            deleteAll = null,
                        ),
                ).build()

            val responses = store.stream(StoreReadRequest.fresh("1")).take(2).toList()

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data(Page.Data("1", null), StoreReadResponseOrigin.Fetcher(api.name)),
                ),
                responses,
            )
        }

    @Test
    fun givenEmptyStoreWhenFailureFromPrimaryApiThenStoreReadResponseOfSecondaryApiResult() =
        testScope.runTest {
            val ttl = null
            val fail = true

            val hardcodedPagesFetcher = Fetcher.of<String, Page> { key -> hardcodedPages.get(key) }
            val secondaryApiFetcher =
                Fetcher.withFallback(
                    secondaryApi.name,
                    hardcodedPagesFetcher,
                ) { key -> secondaryApi.get(key) }

            val store =
                StoreBuilder.from(
                    fetcher = Fetcher.withFallback(api.name, secondaryApiFetcher) { key -> api.fetch(key, fail, ttl) },
                    sourceOfTruth =
                        SourceOfTruth.of(
                            nonFlowReader = { key -> pagesDatabase.get(key) },
                            writer = { key, page -> pagesDatabase.put(key, page) },
                            delete = null,
                            deleteAll = null,
                        ),
                ).build()

            val responses = store.stream(StoreReadRequest.fresh("1")).take(2).toList()

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data(
                        Page.Data("1", null),
                        StoreReadResponseOrigin.Fetcher(secondaryApiFetcher.name),
                    ),
                ),
                responses,
            )
        }

    @Test
    fun givenEmptyStoreWhenFailureFromPrimaryAndSecondaryApisThenStoreReadResponseOfHardcodedData() =

        testScope.runTest {
            val ttl = null
            val fail = true

            val hardcodedPagesFetcher = Fetcher.of<String, Page> { key -> hardcodedPages.get(key) }
            val throwingSecondaryApiFetcher =
                Fetcher.withFallback(secondaryApi.name, hardcodedPagesFetcher) { throw Exception() }

            val store =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.withFallback(api.name, throwingSecondaryApiFetcher) { key ->
                            api.fetch(
                                key,
                                fail,
                                ttl,
                            )
                        },
                    sourceOfTruth =
                        SourceOfTruth.of(
                            nonFlowReader = { key -> pagesDatabase.get(key) },
                            writer = { key, page -> pagesDatabase.put(key, page) },
                            delete = null,
                            deleteAll = null,
                        ),
                ).build()

            val responses = store.stream(StoreReadRequest.fresh("1")).take(2).toList()

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data(
                        Page.Data("1", null),
                        StoreReadResponseOrigin.Fetcher(hardcodedPagesFetcher.name),
                    ),
                ),
                responses,
            )
        }
}
