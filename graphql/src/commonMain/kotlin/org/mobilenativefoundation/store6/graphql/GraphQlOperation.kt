package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * One GraphQL operation: an executable document and the name of the operation to run.
 *
 * The document is opaque to Store — it is carried to the [GraphQlExecutor] unparsed, so any
 * source of documents (hand-written strings, generated constants, persisted-query ids embedded
 * by the executor) works. [name] must match the operation name used in [GraphQlOperationKey]
 * instances fetched through this operation; [graphQlFetcher] rejects mismatched keys.
 *
 * @throws IllegalArgumentException if [document] or [name] is blank
 */
@ExperimentalStoreApi
public class GraphQlOperation(
    /** The executable GraphQL document, passed to the executor unparsed. */
    public val document: String,
    /** The name of the operation to execute from [document]. */
    public val name: String,
) {
    init {
        require(document.isNotBlank()) { "GraphQlOperation requires a non-blank document." }
        require(name.isNotBlank()) {
            "GraphQlOperation requires a non-blank name; use the operation's declared name so " +
                "keys and fetchers can be matched."
        }
    }

    /**
     * Returns the [GraphQlOperationKey] for executing this operation with [variables].
     *
     * @param variables the execution's variables; defaults to none
     * @return a key whose operation name is [name] and whose namespace is the key default
     */
    public fun key(variables: GraphQlVariables = GraphQlVariables.Empty): GraphQlOperationKey =
        GraphQlOperationKey(operationName = name, variables = variables)

    override fun toString(): String = "GraphQlOperation($name)"
}
