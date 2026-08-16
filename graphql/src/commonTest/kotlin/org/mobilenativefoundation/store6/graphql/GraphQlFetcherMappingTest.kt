@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GraphQlFetcherMappingTest {
    private data class User(val id: String, val name: String)

    private val operation =
        GraphQlOperation(
            document = "query GetUser(\$id: ID!) { user(id: \$id) { name } }",
            name = "GetUser",
        )
    private val key = operation.key(graphQlVariables { put("id", "42") })
    private val user = User(id = "42", name = "Ada")

    @Test
    fun data_withoutErrors_mapsToSuccessWithEtag() = runTest {
        val fetcher =
            graphQlFetcher(operation) { _ ->
                GraphQlExecutorResult.Data(data = user, etag = "v1")
            }

        val result = fetcher.fetch(key, etag = null)

        val success = assertIs<FetcherResult.Success<User>>(result)
        assertSame(user, success.value)
        assertEquals("v1", success.etag)
    }

    @Test
    fun data_withoutEtag_mapsToSuccessWithNullEtag() = runTest {
        val fetcher =
            graphQlFetcher(operation) { _ ->
                GraphQlExecutorResult.Data(data = user)
            }

        val success = assertIs<FetcherResult.Success<User>>(fetcher.fetch(key, etag = null))
        assertNull(success.etag)
    }

    @Test
    fun notModified_mapsToFetcherNotModifiedWithEtag() = runTest {
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                GraphQlExecutorResult.NotModified(etag = "v2")
            }

        val result = fetcher.fetch(key, etag = "v1")

        val notModified = assertIs<FetcherResult.NotModified>(result)
        assertEquals("v2", notModified.etag)
    }

    @Test
    fun executorThrow_mapsToFetcherErrorWithSameCause() = runTest {
        val transportFailure = RuntimeException("socket closed")
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                throw transportFailure
            }

        val result = fetcher.fetch(key, etag = null)

        val error = assertIs<FetcherResult.Error>(result)
        assertSame(transportFailure, error.cause)
    }

    @Test
    fun cancellation_propagatesInsteadOfMappingToError() = runTest {
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                throw CancellationException("caller cancelled")
            }

        assertFailsWith<CancellationException> { fetcher.fetch(key, etag = null) }
    }

    @Test
    fun nullDataWithoutErrors_isProtocolViolationError() = runTest {
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                GraphQlExecutorResult.Data(data = null)
            }

        val error = assertIs<FetcherResult.Error>(fetcher.fetch(key, etag = null))

        val cause = assertIs<IllegalStateException>(error.cause)
        assertTrue("GetUser" in cause.message.orEmpty())
    }

    @Test
    fun nullDataWithErrors_mapsToOperationException() = runTest {
        val executorErrors =
            listOf(
                GraphQlError(
                    message = "User not found",
                    path =
                        listOf(
                            GraphQlError.PathSegment.Field("user"),
                        ),
                ),
            )
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                GraphQlExecutorResult.Data(data = null, errors = executorErrors)
            }

        val error = assertIs<FetcherResult.Error>(fetcher.fetch(key, etag = null))

        val cause = assertIs<GraphQlOperationException>(error.cause)
        assertEquals("GetUser", cause.operationName)
        assertEquals(executorErrors, cause.errors)
        assertTrue("GetUser" in cause.message.orEmpty())
        assertTrue("User not found" in cause.message.orEmpty())
    }

    @Test
    fun partialData_defaultPolicy_failsWithOperationException() = runTest {
        val executorErrors = listOf(GraphQlError(message = "field resolution failed"))
        val fetcher =
            graphQlFetcher(operation) { _ ->
                GraphQlExecutorResult.Data(data = user, errors = executorErrors)
            }

        val error = assertIs<FetcherResult.Error>(fetcher.fetch(key, etag = null))

        val cause = assertIs<GraphQlOperationException>(error.cause)
        assertEquals(executorErrors, cause.errors)
    }

    @Test
    fun partialData_adoptPolicy_mapsToSuccessKeepingData() = runTest {
        val fetcher =
            graphQlFetcher(
                operation = operation,
                partialDataPolicy = GraphQlPartialDataPolicy.AdoptPartialData,
            ) { _ ->
                GraphQlExecutorResult.Data(
                    data = user,
                    errors = listOf(GraphQlError(message = "field resolution failed")),
                    etag = "v3",
                )
            }

        val success = assertIs<FetcherResult.Success<User>>(fetcher.fetch(key, etag = null))
        assertSame(user, success.value)
        assertEquals("v3", success.etag)
    }

    @Test
    fun adoptPolicy_nullDataWithErrors_stillMapsToOperationException() = runTest {
        val fetcher =
            graphQlFetcher<User>(
                operation = operation,
                partialDataPolicy = GraphQlPartialDataPolicy.AdoptPartialData,
            ) { _ ->
                GraphQlExecutorResult.Data(
                    data = null,
                    errors = listOf(GraphQlError(message = "hard failure")),
                )
            }

        val error = assertIs<FetcherResult.Error>(fetcher.fetch(key, etag = null))
        assertIs<GraphQlOperationException>(error.cause)
    }

    @Test
    fun keyOperationMismatch_mapsToErrorNamingBothWithoutExecuting() = runTest {
        var executed = false
        val fetcher =
            graphQlFetcher<User>(operation) { _ ->
                executed = true
                GraphQlExecutorResult.Data(data = user)
            }
        val mismatchedKey = GraphQlOperationKey(operationName = "GetAccount")

        val error = assertIs<FetcherResult.Error>(fetcher.fetch(mismatchedKey, etag = null))

        val cause = assertIs<IllegalArgumentException>(error.cause)
        assertTrue("GetUser" in cause.message.orEmpty())
        assertTrue("GetAccount" in cause.message.orEmpty())
        assertEquals(false, executed)
    }

    @Test
    fun request_threadsOperationVariablesAndEngineEtag() = runTest {
        val requests = mutableListOf<GraphQlRequest>()
        val fetcher =
            graphQlFetcher(operation) { request ->
                requests.add(request)
                GraphQlExecutorResult.Data(data = user)
            }

        fetcher.fetch(key, etag = "engine-etag")

        assertEquals(1, requests.size)
        assertSame(operation, requests.single().operation)
        assertEquals(key.variables, requests.single().variables)
        assertEquals("engine-etag", requests.single().etag)
    }

    @Test
    fun request_withoutConditionalPlan_hasNullEtag() = runTest {
        val requests = mutableListOf<GraphQlRequest>()
        val fetcher =
            graphQlFetcher(operation) { request ->
                requests.add(request)
                GraphQlExecutorResult.Data(data = user)
            }

        fetcher.fetch(key, etag = null)

        assertNull(requests.single().etag)
    }

    @Test
    fun graphQlError_exposesMessageAndPathSegments() {
        val error =
            GraphQlError(
                message = "boom",
                path =
                    listOf(
                        GraphQlError.PathSegment.Field("users"),
                        GraphQlError.PathSegment.Index(0),
                        GraphQlError.PathSegment.Field("name"),
                    ),
            )

        assertEquals("boom", error.message)
        assertEquals("users", (error.path[0] as GraphQlError.PathSegment.Field).name)
        assertEquals(0, (error.path[1] as GraphQlError.PathSegment.Index).index)
        assertEquals("name", (error.path[2] as GraphQlError.PathSegment.Field).name)
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
