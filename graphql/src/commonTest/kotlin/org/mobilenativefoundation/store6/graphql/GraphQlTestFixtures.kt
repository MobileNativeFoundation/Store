@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.store

internal data class TestUser(
    val id: String,
    val name: String,
)

internal val GET_USER =
    GraphQlOperation(
        document = "query GetUser(\$id: ID!) { user(id: \$id) { id name } }",
        name = "GetUser",
    )

internal fun userVariables(id: String): GraphQlVariables = graphQlVariables { put("id", id) }

internal class ScriptedGraphQlExecutor<V : Any> : GraphQlExecutor<V> {
    private val scripts =
        MutableStateFlow<Map<GraphQlVariables, List<suspend (String?) -> GraphQlExecutorResult<V>>>>(emptyMap())
    private val recordedEtags = MutableStateFlow<Map<GraphQlVariables, List<String?>>>(emptyMap())

    fun enqueue(
        variables: GraphQlVariables,
        vararg results: GraphQlExecutorResult<V>,
    ) {
        results.forEach { result -> enqueueAction(variables) { result } }
    }

    fun enqueueAction(
        variables: GraphQlVariables,
        result: suspend (etag: String?) -> GraphQlExecutorResult<V>,
    ) {
        scripts.update { current -> current + (variables to (current[variables].orEmpty() + result)) }
    }

    fun callCount(variables: GraphQlVariables): Int = recordedEtags.value[variables].orEmpty().size

    fun etags(variables: GraphQlVariables): List<String?> = recordedEtags.value[variables].orEmpty()

    override suspend fun execute(request: GraphQlRequest): GraphQlExecutorResult<V> {
        recordedEtags.update { current ->
            current + (request.variables to (current[request.variables].orEmpty() + request.etag))
        }
        return pop(request.variables)?.invoke(request.etag)
            ?: throw IllegalStateException(
                "No scripted GraphQL result for ${request.operation.name}(${request.variables}).",
            )
    }

    private fun pop(variables: GraphQlVariables): (suspend (String?) -> GraphQlExecutorResult<V>)? {
        while (true) {
            val current = scripts.value
            val queue = current[variables].orEmpty()
            val head = queue.firstOrNull() ?: return null
            if (scripts.compareAndSet(current, current + (variables to queue.drop(1)))) return head
        }
    }
}

internal fun userStore(
    executor: ScriptedGraphQlExecutor<TestUser>,
    partialDataPolicy: GraphQlPartialDataPolicy = GraphQlPartialDataPolicy.FailOnErrors,
): Store<GraphQlOperationKey, TestUser> =
    store {
        graphQlFetcher(GET_USER, partialDataPolicy, executor)
    }
