@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KtorTypesTest {
    @Test
    fun defaultMapper_returnsDefer() =
        runTest {
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(content = "", status = HttpStatusCode.NotFound)
                    }
                }
            }.use { client ->
                val response = client.get("https://example.test/item")
                val exchange =
                    KtorExchange(
                        status = response.status,
                        method = HttpMethod.Get,
                        url = "https://example.test/item",
                        conditional = false,
                        response = response,
                    )

                assertEquals(HttpStatusCode.NotFound, exchange.status)
                assertEquals(HttpMethod.Get, exchange.method)
                assertEquals("https://example.test/item", exchange.url)
                assertEquals(false, exchange.conditional)
                assertSame(response, exchange.response)
                assertEquals(KtorOutcome.Defer, KtorErrorMapper.Default.map(exchange))
            }
        }

    @Test
    fun fetchException_preservesStatusMethodAndUrl() {
        val cause = IllegalStateException("upstream")
        val exception =
            KtorFetchException(
                status = HttpStatusCode.NotFound,
                method = HttpMethod.Get,
                url = "https://example.test/item",
                message = "not found",
                cause = cause,
            )

        assertEquals(HttpStatusCode.NotFound, exception.status)
        assertEquals(HttpMethod.Get, exception.method)
        assertEquals("https://example.test/item", exception.url)
        assertEquals("not found", exception.message)
        assertSame(cause, exception.cause)
    }

    @Test
    fun outcomeCases_construct() {
        val exception =
            KtorFetchException(
                status = HttpStatusCode.InternalServerError,
                method = HttpMethod.Head,
                url = "https://example.test/fail",
                message = "server error",
            )
        val fail = KtorOutcome.Fail(exception)
        val notModified = KtorOutcome.NotModified("etag-1")

        assertSame(exception, fail.exception)
        assertEquals("etag-1", notModified.validatorToken)
        assertNull(KtorOutcome.NotModified(null).validatorToken)
    }
}
