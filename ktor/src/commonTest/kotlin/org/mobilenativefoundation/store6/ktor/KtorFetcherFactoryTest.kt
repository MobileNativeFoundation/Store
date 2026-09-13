@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KtorFetcherFactoryTest {
    @Test
    fun builderExtension_get_returnsDecodedValue() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "decoded value",
                        status = HttpStatusCode.OK,
                    )
                }

            HttpClient(engine).use { client ->
                val store =
                    store {
                        ktorFetcher(
                            client = client,
                            decode = { response -> response.bodyAsText() },
                            configureRequest = { key ->
                                url("https://example.test/items/${key.canonicalId()}")
                            },
                        )
                    }
                try {
                    assertEquals("decoded value", store.get(FactoryKey("1")))
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun standaloneFactory_fetch_returnsSuccess() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "decoded value",
                        status = HttpStatusCode.OK,
                    )
                }

            HttpClient(engine).use { client ->
                val fetcher =
                    ktorFetcher<FactoryKey, String>(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )

                val result = assertIs<FetcherResult.Success<String>>(fetcher.fetch(FactoryKey("1"), null))
                assertEquals("decoded value", result.value)
            }
        }
}

private class FactoryKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-factory")

    override fun canonicalId(): String = id
}
