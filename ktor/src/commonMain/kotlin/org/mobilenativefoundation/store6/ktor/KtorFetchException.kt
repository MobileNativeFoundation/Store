package org.mobilenativefoundation.store6.ktor

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** The typed failure carried as StoreError.Fetch.cause for a non-adopted HTTP status outcome. */
@ExperimentalStoreApi
public class KtorFetchException(
    /** Always an HTTP status; transport and decode failures keep their original exception. */
    public val status: HttpStatusCode,
    public val method: HttpMethod,
    public val url: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
