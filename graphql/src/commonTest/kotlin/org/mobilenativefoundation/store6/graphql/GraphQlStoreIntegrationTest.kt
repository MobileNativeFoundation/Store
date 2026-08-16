@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class GraphQlStoreIntegrationTest {
    private val ada = TestUser(id = "1", name = "Ada")
    private val revisedAda = TestUser(id = "1", name = "Ada Lovelace")

    @Test
    fun documentCache_servesResidentValueWithoutReexecution() = runTest {
        val variables = userVariables("1")
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(variables, GraphQlExecutorResult.Data(data = ada))
        val store = userStore(executor)

        try {
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(1, executor.callCount(variables))
        } finally {
            store.close()
        }
    }

    @Test
    fun documentCache_variableOrderDoesNotSplitEntries() = runTest {
        val variables =
            graphQlVariables {
                put("id", "1")
                put("locale", "en")
            }
        val reordered =
            graphQlVariables {
                put("locale", "en")
                put("id", "1")
            }
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(variables, GraphQlExecutorResult.Data(data = ada))
        val store = userStore(executor)

        try {
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(ada, store.get(GET_USER.key(reordered)))
            assertEquals(1, executor.callCount(variables))
            assertEquals(1, executor.callCount(reordered))
        } finally {
            store.close()
        }
    }

    @Test
    fun concurrentReaders_shareOneExecution() = runTest {
        val variables = userVariables("1")
        val executionEntered = CompletableDeferred<Unit>()
        val releaseExecution = CompletableDeferred<Unit>()
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueueAction(variables) {
            executionEntered.complete(Unit)
            releaseExecution.await()
            GraphQlExecutorResult.Data(data = ada)
        }
        val store = userStore(executor)

        try {
            val first = async { store.get(GET_USER.key(variables)) }
            executionEntered.awaitFromDefaultContext()
            val second = async { store.get(GET_USER.key(variables)) }
            releaseExecution.complete(Unit)

            assertEquals(ada, first.await())
            assertEquals(ada, second.await())
            assertEquals(1, executor.callCount(variables))
        } finally {
            releaseExecution.complete(Unit)
            store.close()
        }
    }

    @Test
    fun mustBeFresh_reexecutesInsteadOfServingResident() = runTest {
        val variables = userVariables("1")
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(
            variables,
            GraphQlExecutorResult.Data(data = ada),
            GraphQlExecutorResult.Data(data = revisedAda),
        )
        val store = userStore(executor)

        try {
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(revisedAda, store.get(GET_USER.key(variables), Freshness.MustBeFresh))
            assertEquals(2, executor.callCount(variables))
        } finally {
            store.close()
        }
    }

    @Test
    fun conditionalRefetch_notModified_emitsRevalidatedAndThreadsEtags() = runTest {
        val variables = userVariables("1")
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(
            variables,
            GraphQlExecutorResult.Data(data = ada, etag = "e1"),
            // A cold-baseline 304 commits ObsoleteRevalidation and legally self-heals
            // with exactly one replanned conditional fetch.
            GraphQlExecutorResult.NotModified("e1"),
            GraphQlExecutorResult.NotModified("e1"),
        )
        val store = userStore(executor)
        val key = GET_USER.key(variables)

        try {
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals(ada, assertIs<StoreResult.Data<TestUser>>(awaitItem()).value)
                store.invalidate(key)

                while (true) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data -> {
                            if (item.origin == Origin.FETCHER && !item.isStale) {
                                fail("legacy fresh FETCHER Data must be replaced by Revalidated")
                            }
                        }
                        is StoreResult.Revalidated -> break
                        else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }

            val recordedEtags = executor.etags(variables)
            assertTrue(
                recordedEtags.size in 2..3,
                "the 304 cycle may self-heal one obsolete cold-baseline launch",
            )
            assertEquals(null, recordedEtags[0])
            recordedEtags.drop(1).forEach { etag ->
                assertEquals("e1", etag, "conditional replans must carry the recorded ETag")
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun executorErrors_surfaceAsFetchErrorFramesCarryingOperationException() = runTest {
        val variables = userVariables("missing")
        val responseErrors =
            listOf(
                GraphQlError(
                    message = "User not found",
                    path = listOf(GraphQlError.PathSegment.Field("user")),
                ),
            )
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(
            variables,
            GraphQlExecutorResult.Data(data = null, errors = responseErrors),
        )
        val store = userStore(executor)

        try {
            store.stream(GET_USER.key(variables)).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val frame = assertIs<StoreResult.Error>(awaitItem())
                val fetchError = assertIs<StoreError.Fetch>(frame.error)
                val cause = assertIs<GraphQlOperationException>(fetchError.cause)
                assertEquals("GetUser", cause.operationName)
                assertEquals(responseErrors, cause.errors)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun mustBeFreshGet_failsWithOperationExceptionCause() = runTest {
        val variables = userVariables("missing")
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(
            variables,
            GraphQlExecutorResult.Data(
                data = null,
                errors = listOf(GraphQlError(message = "User not found")),
            ),
        )
        val store = userStore(executor)

        try {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(GET_USER.key(variables), Freshness.MustBeFresh)
                }
            val fetchError = assertIs<StoreError.Fetch>(failure.error)
            assertIs<GraphQlOperationException>(fetchError.cause)
        } finally {
            store.close()
        }
    }

    @Test
    fun adoptPartialData_cachesThePartialValue() = runTest {
        val variables = userVariables("1")
        val executor = ScriptedGraphQlExecutor<TestUser>()
        executor.enqueue(
            variables,
            GraphQlExecutorResult.Data(
                data = ada,
                errors = listOf(GraphQlError(message = "avatar resolution failed")),
            ),
        )
        val store = userStore(executor, GraphQlPartialDataPolicy.AdoptPartialData)

        try {
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(ada, store.get(GET_USER.key(variables)))
            assertEquals(1, executor.callCount(variables))
        } finally {
            store.close()
        }
    }
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }

// Preserve Default-dispatch ordering and let the suite-level runTest bound own cancellation.
private suspend fun <T> CompletableDeferred<T>.awaitFromDefaultContext(): T =
    withContext(Dispatchers.Default) {
        await()
    }
