package org.mobilenativefoundation.store6.ktor

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * A read-only view of a completed HTTP exchange, valid only for the duration of the map call.
 *
 * The constructor is public so a [KtorErrorMapper] can be unit-tested without driving a fetch.
 */
@ExperimentalStoreApi
public class KtorExchange(
    public val status: HttpStatusCode,
    public val method: HttpMethod,
    public val url: String,
    /** Sent a conditional header for this request (If-None-Match or If-Modified-Since). */
    public val conditional: Boolean,
    /** The live response; read headers here. Do not retain it past the map call. */
    public val response: HttpResponse,
)
