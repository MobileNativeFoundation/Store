@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KtorHttpCacheGuardTest {
    @Test
    fun httpCacheInstalled_defaultAllow_throwsIllegalArgumentExceptionNamingHttpCache() {
        val engine =
            MockEngine {
                respond(content = "payload", status = HttpStatusCode.OK)
            }

        HttpClient(engine) {
            install(HttpCache)
        }.use { client ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    guardFetcher(client)
                }
            assertContains(failure.message.orEmpty(), "HttpCache")
        }
    }

    @Test
    fun httpCacheInstalled_allowHttpCacheTrue_constructsAndFetchSucceeds() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "payload", status = HttpStatusCode.OK)
                }

            HttpClient(engine) {
                install(HttpCache)
            }.use { client ->
                val fetcher = guardFetcher(client, allowHttpCache = true)
                val result = assertIs<FetcherResult.Success<String>>(fetcher.fetch(GuardKey("1"), null))
                assertEquals("payload", result.value)
            }
        }

    @Test
    fun clientWithoutHttpCache_constructsWithDefaultAllowFalse() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "payload", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val fetcher = guardFetcher(client)
                val result = assertIs<FetcherResult.Success<String>>(fetcher.fetch(GuardKey("1"), null))
                assertEquals("payload", result.value)
            }
        }

    private fun guardFetcher(
        client: HttpClient,
        allowHttpCache: Boolean = false,
    ) = ktorFetcher<GuardKey, String>(
        client = client,
        allowHttpCache = allowHttpCache,
        decode = { response -> response.bodyAsText() },
        configureRequest = { key ->
            url("https://example.test/items/${key.canonicalId()}")
        },
    )
}

private class GuardKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-http-cache-guard")

    override fun canonicalId(): String = id
}
