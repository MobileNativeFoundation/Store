package org.mobilenativefoundation.store.superstore5

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals


sealed class Page {
    data class Data(
        val title: String,
        val ttl: Long? = null
    ) : Page()

    object Empty : Page()
}

interface TestApi {
    suspend fun fetch(key: String, fail: Boolean = false, ttl: Long? = null): Page?
}

class PagesApi : TestApi {

    internal val db = mutableMapOf<String, Page>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    override suspend fun fetch(key: String, fail: Boolean, ttl: Long?): Page {
        if (fail) {
            throw Exception()
        }

        return db[key] ?: Page.Empty
    }
}


interface TestWarehouse<Key : Any, Output : Any> : Warehouse<Key, Output> {
    suspend fun get(key: String, fail: Boolean = false, ttl: Long? = null): Output?
}


class SecondaryApi() : TestWarehouse<String, Page> {

    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    override suspend fun get(key: String): Page = db[key] ?: Page.Empty

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    override suspend fun get(key: String, fail: Boolean, ttl: Long?): Page {
        if (fail) {
            throw Exception()
        }

        val page = get(key)
        return if (ttl != null && page is Page.Data) {
            page.copy(ttl = ttl)
        } else {
            page
        }
    }

}

class HardcodedPages : Warehouse<String, Page.Data> {
    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("One")
        db["2"] = Page.Data("Two")
        db["3"] = Page.Data("Three")
    }

    override suspend fun get(key: String): Page.Data = db[key]!!
}

class PagesDatabase {
    private val db: MutableMap<String, Page?> = mutableMapOf()

    fun put(key: String, input: Page): Boolean {
        db[key] = input
        return true
    }

    fun get(key: String): Page? = db[key]
}


class SuperstoreTests {
    private val testScope = TestScope()
    private lateinit var api: PagesApi
    private lateinit var secondaryApi: Warehouse<String, Page>
    private lateinit var hardcodedPages: Warehouse<String, Page.Data>
    private lateinit var pagesDatabase: PagesDatabase

    @BeforeTest
    fun before() {
        api = PagesApi()
        secondaryApi = SecondaryApi()
        hardcodedPages = HardcodedPages()
        pagesDatabase = PagesDatabase()
    }

    @Test
    fun givenEmptyStoreWhenSuccessFromMainApiThenSuperstoreResponseOfMainApiResult() = testScope.runTest {
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
    fun givenEmptyStoreWhenFailureFromMainApiThenSuperstoreResponseOfSecondaryApiResult() = testScope.runTest {
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
    fun givenEmptyStoreWhenFailureFromMainAndSecondaryApisThenSuperstoreResponseOfHardcodedData() = testScope.runTest {
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