package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * How [graphQlFetcher] treats a response that carries both non-null data and errors.
 *
 * Responses with null data and non-empty errors always fail regardless of policy.
 */
@ExperimentalStoreApi
public sealed interface GraphQlPartialDataPolicy {
    /**
     * The default: any response error fails the fetch with a [GraphQlOperationException], and
     * partial data is discarded. Choose this when a cached value must never contain
     * error-substituted nulls.
     */
    public data object FailOnErrors : GraphQlPartialDataPolicy

    /**
     * Keeps the partial data as a fetch success and drops the errors. Choose this when the
     * decoded type tolerates missing fields; the store then caches the partial value as if it
     * were complete.
     */
    public data object AdoptPartialData : GraphQlPartialDataPolicy
}
