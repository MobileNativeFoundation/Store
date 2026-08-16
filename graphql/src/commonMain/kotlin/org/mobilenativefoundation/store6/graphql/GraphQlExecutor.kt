package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Executes GraphQL operations against a transport chosen by the caller.
 *
 * The executor owns everything Store deliberately does not: JSON encoding of
 * [GraphQlRequest.variables], the HTTP or WebSocket transport, decoding the response `data`
 * into `V`, and translating the response `errors` array into [GraphQlError] values. Throw for
 * transport failures (the fetcher maps the thrown exception to a fetch error); return
 * [GraphQlExecutorResult.Data] for any response the server produced, even an all-errors one.
 *
 * When [GraphQlRequest.etag] is non-null the store engine selected a conditional plan: an
 * executor that supports revalidation (for example via HTTP `If-None-Match`) may return
 * [GraphQlExecutorResult.NotModified] to confirm the cached value. Executors without
 * revalidation support can ignore the ETag and execute normally.
 *
 * @param V the decoded value type produced for successful responses
 */
@ExperimentalStoreApi
public fun interface GraphQlExecutor<V : Any> {
    /** Executes [request] and returns the decoded outcome; throws on transport failure. */
    public suspend fun execute(request: GraphQlRequest): GraphQlExecutorResult<V>
}

/**
 * One operation execution handed to a [GraphQlExecutor].
 *
 * @property operation the operation to execute; its document is carried unparsed
 * @property variables the variables of this execution, taken from the fetched key
 * @property etag the ETag of the cached value when the engine selected a conditional plan,
 * or null for an unconditional fetch
 */
@ExperimentalStoreApi
public class GraphQlRequest(
    public val operation: GraphQlOperation,
    public val variables: GraphQlVariables,
    public val etag: String?,
) {
    override fun toString(): String = "GraphQlRequest(${operation.name}, etag=$etag)"
}

/**
 * The outcome vocabulary a [GraphQlExecutor] returns to the fetcher.
 *
 * @param V the decoded value type
 */
@ExperimentalStoreApi
public sealed interface GraphQlExecutorResult<out V : Any> {
    /**
     * A GraphQL response: decoded `data`, decoded `errors`, or both.
     *
     * A response with a non-null [data] and no [errors] maps to a fetch success. Non-empty
     * [errors] map per the fetcher's [GraphQlPartialDataPolicy]. Null [data] with no [errors]
     * is a protocol violation the fetcher reports as an error.
     *
     * @property data the decoded response data, or null when the response carried none
     * @property errors the decoded response errors; empty when the response carried none
     * @property etag the value identity to record for conditional refetching, or null
     */
    public class Data<V : Any>(
        public val data: V?,
        public val errors: List<GraphQlError> = emptyList(),
        public val etag: String? = null,
    ) : GraphQlExecutorResult<V>

    /**
     * Confirmation that the value identified by [GraphQlRequest.etag] is still current.
     *
     * @property etag the refreshed ETag, or null to keep the previously recorded tag
     */
    public class NotModified(
        public val etag: String? = null,
    ) : GraphQlExecutorResult<Nothing>
}
