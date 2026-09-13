@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KtorFetcherSmokeTest {
    @Test
    fun successfulResponse_decodesBodyAndStoresEtag() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "decoded value",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val fetcher = smokeFetcher(client)

                val result = assertIs<FetcherResult.Success<String>>(fetcher.fetch(SmokeKey("1"), null))
                assertEquals("decoded value", result.value)
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun conditionalResponse_sendsEtagAndReturnsNotModified() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("\"v1\"", request.headers[HttpHeaders.IfNoneMatch])
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val fetcher = smokeFetcher(client)

                val result =
                    assertIs<FetcherResult.NotModified>(
                        fetcher.fetch(SmokeKey("1"), etag = "\"v1\""),
                    )
                assertEquals("\"v1\"", result.etag)
            }
        }

    private fun smokeFetcher(client: HttpClient) =
        createKtorFetcher<SmokeKey, String>(
            client = client,
            notFoundPolicy = KtorNotFoundPolicy.Error,
            lastModifiedFallback = true,
            errorMapper = KtorErrorMapper.Default,
            decode = { response -> response.bodyAsText() },
            configureRequest = { key ->
                url("https://example.test/items/${key.canonicalId()}")
            },
        )
}

private class SmokeKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-smoke")

    override fun canonicalId(): String = id
}
