@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A consumer's view of the module's headline extension point. Everything here uses only the
 * published surface — `KtorExchange`'s constructor included — so a mapper can be unit-tested
 * without standing up the fetcher. `ktor/api` pins that the constructor stays published.
 */
class KtorErrorMapperContractTest {
    @Test
    fun customMapper_drivenThroughEveryOutcomeBranch() =
        runTest {
            withResponse { response ->
                val mapper = ProductionShapedMapper

                assertEquals(
                    KtorOutcome.Defer,
                    mapper.map(exchange(HttpStatusCode.OK, conditional = false, response = response)),
                )

                assertEquals(
                    KtorOutcome.Delete,
                    mapper.map(exchange(HttpStatusCode.Gone, conditional = false, response = response)),
                )

                val notModified =
                    assertIs<KtorOutcome.NotModified>(
                        mapper.map(
                            exchange(HttpStatusCode.NotModified, conditional = true, response = response),
                        ),
                    )
                assertNull(notModified.validatorToken)

                val fail =
                    assertIs<KtorOutcome.Fail>(
                        mapper.map(
                            exchange(HttpStatusCode.TooManyRequests, conditional = false, response = response),
                        ),
                    )
                assertEquals(HttpStatusCode.TooManyRequests, fail.exception.status)
                assertEquals(HttpMethod.Get, fail.exception.method)
                assertEquals(URL, fail.exception.url)
            }
        }

    @Test
    fun publiclyConstructedExchange_exposesEveryFieldVerbatim() =
        runTest {
            withResponse { response ->
                val exchange = exchange(HttpStatusCode.NotModified, conditional = true, response = response)

                assertEquals(HttpStatusCode.NotModified, exchange.status)
                assertEquals(HttpMethod.Get, exchange.method)
                assertEquals(URL, exchange.url)
                assertEquals(true, exchange.conditional)
                assertSame(response, exchange.response)
            }
        }

    private fun exchange(
        status: HttpStatusCode,
        conditional: Boolean,
        response: HttpResponse,
    ): KtorExchange =
        KtorExchange(
            status = status,
            method = HttpMethod.Get,
            url = URL,
            conditional = conditional,
            response = response,
        )

    private suspend fun withResponse(block: suspend (HttpResponse) -> Unit) {
        HttpClient(MockEngine) {
            engine {
                addHandler { respond(content = "", status = HttpStatusCode.OK) }
            }
        }.use { client -> block(client.get(URL)) }
    }

    private companion object {
        const val URL = "https://example.test/item"

        /**
         * The shape a consumer actually writes: delete on a tombstone, keep freshness on a
         * validated 304, fail loudly on throttling, defer everything else.
         */
        val ProductionShapedMapper =
            KtorErrorMapper { exchange ->
                when {
                    exchange.status == HttpStatusCode.Gone -> KtorOutcome.Delete

                    exchange.status == HttpStatusCode.NotModified && exchange.conditional ->
                        KtorOutcome.NotModified(null)

                    exchange.status == HttpStatusCode.TooManyRequests ->
                        KtorOutcome.Fail(
                            KtorFetchException(
                                status = exchange.status,
                                method = exchange.method,
                                url = exchange.url,
                                message = "throttled",
                            ),
                        )

                    else -> KtorOutcome.Defer
                }
            }
    }
}
