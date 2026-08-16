package org.mobilenativefoundation.store6.graphql

import kotlinx.coroutines.CancellationException
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/**
 * Creates a [Fetcher] that executes [operation] through [executor] for every fetched key.
 *
 * The returned fetcher serves one operation: a [GraphQlOperationKey] whose
 * [GraphQlOperationKey.operationName] differs from [operation]'s name fails the fetch without
 * executing. Executor outcomes map to the fetch vocabulary as follows: a thrown exception
 * (other than [CancellationException], which propagates) becomes a fetch error; a
 * [GraphQlExecutorResult.NotModified] becomes [FetcherResult.NotModified]; a response with
 * non-null data and no errors becomes [FetcherResult.Success]; a response with errors follows
 * [partialDataPolicy]; and a response with neither data nor errors is reported as a protocol
 * violation.
 *
 * @param V the decoded value type cached by the store
 * @param operation the operation every fetched key must belong to
 * @param partialDataPolicy how responses with both data and errors are treated
 * @param executor the transport owned by the caller
 * @return a fetcher for installation via [StoreBuilder]
 */
@ExperimentalStoreApi
public fun <V : Any> graphQlFetcher(
    operation: GraphQlOperation,
    partialDataPolicy: GraphQlPartialDataPolicy = GraphQlPartialDataPolicy.FailOnErrors,
    executor: GraphQlExecutor<V>,
): Fetcher<GraphQlOperationKey, V> = GraphQlFetcher(operation, partialDataPolicy, executor)

/**
 * Installs a [graphQlFetcher] built from [operation], [partialDataPolicy], and [executor] as
 * this store's fetch source. The last fetcher registration wins, matching the other
 * [StoreBuilder] fetcher install points.
 *
 * @param V the decoded value type cached by the store
 * @param operation the operation every fetched key must belong to
 * @param partialDataPolicy how responses with both data and errors are treated
 * @param executor the transport owned by the caller
 */
@ExperimentalStoreApi
public fun <V : Any> StoreBuilder<GraphQlOperationKey, V>.graphQlFetcher(
    operation: GraphQlOperation,
    partialDataPolicy: GraphQlPartialDataPolicy = GraphQlPartialDataPolicy.FailOnErrors,
    executor: GraphQlExecutor<V>,
) {
    fetcher(GraphQlFetcher(operation, partialDataPolicy, executor))
}

@OptIn(DelicateStoreApi::class)
@ExperimentalStoreApi
private class GraphQlFetcher<V : Any>(
    private val operation: GraphQlOperation,
    private val partialDataPolicy: GraphQlPartialDataPolicy,
    private val executor: GraphQlExecutor<V>,
) : Fetcher<GraphQlOperationKey, V> {
    override suspend fun fetch(
        key: GraphQlOperationKey,
        etag: String?,
    ): FetcherResult<V> {
        if (key.operationName != operation.name) {
            return FetcherResult.Error(
                IllegalArgumentException(
                    "graphQlFetcher for operation '${operation.name}' received a key for " +
                        "operation '${key.operationName}'. A store serves one operation; build " +
                        "keys with GraphQlOperation.key(...) on the fetcher's operation.",
                ),
            )
        }
        val result =
            try {
                executor.execute(GraphQlRequest(operation, key.variables, etag))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (transportFailure: Throwable) {
                return FetcherResult.Error(transportFailure)
            }
        return when (result) {
            is GraphQlExecutorResult.NotModified -> FetcherResult.NotModified(result.etag)
            is GraphQlExecutorResult.Data -> mapData(result)
        }
    }

    private fun mapData(result: GraphQlExecutorResult.Data<V>): FetcherResult<V> {
        val data = result.data
        if (result.errors.isEmpty()) {
            return if (data != null) {
                FetcherResult.Success(data, result.etag)
            } else {
                FetcherResult.Error(
                    IllegalStateException(
                        "GraphQL executor for operation '${operation.name}' returned neither " +
                            "data nor errors; a GraphQL response must carry at least one of " +
                            "the two.",
                    ),
                )
            }
        }
        if (data != null && partialDataPolicy == GraphQlPartialDataPolicy.AdoptPartialData) {
            return FetcherResult.Success(data, result.etag)
        }
        return FetcherResult.Error(GraphQlOperationException(operation.name, result.errors))
    }
}
