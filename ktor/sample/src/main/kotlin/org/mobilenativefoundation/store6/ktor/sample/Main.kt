@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor.sample

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.ktor.KtorFetchException
import org.mobilenativefoundation.store6.ktor.KtorNotFoundPolicy
import org.mobilenativefoundation.store6.ktor.ktorFetcher

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun runSample() {
    scene200ThenConditional304()
    scene500TypedError()
    scene404Policy()
    sceneLastModifiedRoundTrip()
}

private suspend fun scene200ThenConditional304() {
    val recorded = mutableListOf<RecordedRequest>()
    val engine =
        MockEngine { request ->
            recorded += request.recorded()
            if (recorded.size == 1) {
                respond(
                    content = BODY,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag, ETAG),
                )
            } else {
                check(request.headers[HttpHeaders.IfNoneMatch] == ETAG) {
                    "expected If-None-Match $ETAG, was ${request.headers[HttpHeaders.IfNoneMatch]}"
                }
                respond(
                    content = "",
                    status = HttpStatusCode.NotModified,
                    headers = headersOf(HttpHeaders.ETag, ETAG),
                )
            }
        }

    HttpClient(engine).use { client ->
        val itemStore = itemStore(client)
        val key = ItemKey("1")
        try {
            check(itemStore.get(key) == BODY)
            itemStore.invalidate(key)
            itemStore.stream(key).first { result -> result is StoreResult.Revalidated }
            check(itemStore.get(key) == BODY)
            val validators = recorded.map { request -> request.ifNoneMatch }
            checkRevalidationShape(validators, ETAG)
            println("Scene 1: 200 then conditional 304 revalidated; recorded If-None-Match=$validators")
        } finally {
            itemStore.close()
        }
    }
}

private suspend fun scene500TypedError() {
    val engine =
        MockEngine {
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }

    HttpClient(engine).use { client ->
        val itemStore = itemStore(client)
        try {
            val failure = runCatching { itemStore.get(ItemKey("500")) }.exceptionOrNull()
            val storeFailure = checkNotNull(failure as? StoreException) { "expected StoreException, was $failure" }
            val fetchError = checkNotNull(storeFailure.error as? StoreError.Fetch) { "expected StoreError.Fetch" }
            val ktorFailure =
                checkNotNull(fetchError.cause as? KtorFetchException) {
                    "expected KtorFetchException, was ${fetchError.cause}"
                }
            check(ktorFailure.status == HttpStatusCode.InternalServerError)
            println("Scene 2: 500 surfaced KtorFetchException status=${ktorFailure.status}")
        } finally {
            itemStore.close()
        }
    }
}

private suspend fun scene404Policy() {
    val errorEngine =
        MockEngine {
            respond(content = "", status = HttpStatusCode.NotFound)
        }
    HttpClient(errorEngine).use { client ->
        val itemStore = itemStore(client, notFoundPolicy = KtorNotFoundPolicy.Error)
        try {
            val failure = runCatching { itemStore.get(ItemKey("404")) }.exceptionOrNull()
            val storeFailure = checkNotNull(failure as? StoreException) { "expected StoreException, was $failure" }
            val fetchError = checkNotNull(storeFailure.error as? StoreError.Fetch) { "expected StoreError.Fetch" }
            val ktorFailure =
                checkNotNull(fetchError.cause as? KtorFetchException) {
                    "expected KtorFetchException, was ${fetchError.cause}"
                }
            check(ktorFailure.status == HttpStatusCode.NotFound)

            val deleteEngine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }
            HttpClient(deleteEngine).use { deleteClient ->
                val deleteStore = itemStore(deleteClient, notFoundPolicy = KtorNotFoundPolicy.Delete)
                try {
                    val deletedFailure = runCatching { deleteStore.get(ItemKey("404")) }.exceptionOrNull()
                    val deletedStoreFailure =
                        checkNotNull(deletedFailure as? StoreException) {
                            "expected StoreException, was $deletedFailure"
                        }
                    val missing = checkNotNull(deletedStoreFailure.error as? StoreError.Missing) {
                        "expected StoreError.Missing, was ${deletedStoreFailure.error}"
                    }
                    check(missing.message.lowercase().contains("deleted"))
                    println(
                        "Scene 3: Error policy surfaced ${ktorFailure.status}; " +
                            "Delete policy cleared with Missing",
                    )
                } finally {
                    deleteStore.close()
                }
            }
        } finally {
            itemStore.close()
        }
    }
}

private suspend fun sceneLastModifiedRoundTrip() {
    val recorded = mutableListOf<RecordedRequest>()
    val engine =
        MockEngine { request ->
            recorded += request.recorded()
            if (recorded.size == 1) {
                respond(
                    content = BODY,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                )
            } else {
                check(request.headers[HttpHeaders.IfModifiedSince] == LM_DATE) {
                    "expected If-Modified-Since $LM_DATE, was ${request.headers[HttpHeaders.IfModifiedSince]}"
                }
                respond(
                    content = "",
                    status = HttpStatusCode.NotModified,
                    headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                )
            }
        }

    HttpClient(engine).use { client ->
        val itemStore = itemStore(client)
        val key = ItemKey("lm")
        try {
            check(itemStore.get(key) == BODY)
            itemStore.invalidate(key)
            itemStore.stream(key).first { result -> result is StoreResult.Revalidated }
            check(itemStore.get(key) == BODY)
            val validators = recorded.map { request -> request.ifModifiedSince }
            checkRevalidationShape(validators, LM_DATE)
            println("Scene 4: Last-Modified 200 then If-Modified-Since 304 revalidated; recorded IMS=$validators")
        } finally {
            itemStore.close()
        }
    }
}

private fun itemStore(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
): Store<ItemKey, String> =
    store {
        ktorFetcher(
            client = client,
            notFoundPolicy = notFoundPolicy,
            decode = { response -> response.bodyAsText() },
            configureRequest = { key ->
                url("https://example.test/items/${key.canonicalId()}")
            },
        )
    }

/**
 * The split assertion: exactly one unconditional request, and every later request carries
 * [validator].
 *
 * The count itself stays tolerant here, and only here. The sample drives the store on a real
 * dispatcher and cancels the stream the instant `Revalidated` arrives, so the `get` that follows
 * can launch a second revalidation before the first one's freshness metadata lands — an artifact
 * of how the scene observes the store, not of the kit. `KtorStoreIntegrationTest` pins the exact
 * count under virtual time, gated on Turbine's `expectNoEvents()`, and that is where a regression
 * that adds a request per revalidation gets caught.
 *
 * Only the Last-Modified scene has ever been observed taking the extra request — twice in ten
 * runs, and never once in the ETag scene. Its 304 carries no ETag, so it produces
 * `NotModified(null)`, the "keep the previous token" path. Both scenes share this helper because
 * the tolerance costs nothing in the ETag scene, not because both have needed it.
 */
private fun checkRevalidationShape(
    validators: List<String?>,
    validator: String,
) {
    check(validators.firstOrNull() == null) {
        "the cold read must be unconditional, recorded $validators"
    }
    val revalidations = validators.drop(1)
    check(revalidations.isNotEmpty()) {
        "expected at least one revalidation, recorded $validators"
    }
    check(revalidations.all { recordedValidator -> recordedValidator == validator }) {
        "every revalidation must carry $validator, recorded $validators"
    }
}

private fun HttpRequestData.recorded(): RecordedRequest =
    RecordedRequest(
        ifNoneMatch = headers[HttpHeaders.IfNoneMatch],
        ifModifiedSince = headers[HttpHeaders.IfModifiedSince],
    )

private data class RecordedRequest(
    val ifNoneMatch: String?,
    val ifModifiedSince: String?,
)

private data class ItemKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-sample")

    override fun canonicalId(): String = id
}

private const val BODY = "ada"
private const val ETAG = "\"v1\""
private const val LM_DATE = "Wed, 21 Oct 2015 07:28:00 GMT"
private const val SAMPLE_TIMEOUT_MILLIS = 20_000L
