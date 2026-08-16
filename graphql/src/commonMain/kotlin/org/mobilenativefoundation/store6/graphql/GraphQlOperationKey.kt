package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * A [StoreKey] identifying one GraphQL operation execution: the operation name plus its
 * variables.
 *
 * A store keyed this way is a document cache — each distinct variable set caches one response
 * value. Identity is structural: two keys with the same [operationName], equal [variables],
 * and the same namespace value are the same cache entry regardless of variable insertion
 * order.
 *
 * @throws IllegalArgumentException if [operationName] is blank
 */
@ExperimentalStoreApi
public class GraphQlOperationKey(
    /** The GraphQL operation name this key belongs to. */
    public val operationName: String,
    /** The variables of this execution; defaults to none. */
    public val variables: GraphQlVariables = GraphQlVariables.Empty,
    /** The key space; defaults to `graphql:<operationName>`. */
    override val namespace: StoreNamespace = StoreNamespace("graphql:$operationName"),
) : StoreKey {
    init {
        require(operationName.isNotBlank()) {
            "GraphQlOperationKey requires a non-blank operationName; use the operation's " +
                "declared name so keys and fetchers can be matched."
        }
    }

    /**
     * Returns `<operationName>(<canonical variables>)`.
     *
     * The variable rendering is JSON-shaped with object keys sorted in UTF-16 code-unit order,
     * no whitespace, JSON string escaping, significant list order, and explicit `null` distinct
     * from an absent variable. [GraphQlValue.FloatValue] renders through the runtime's
     * `Double.toString`, which differs across Kotlin targets; prefer int or string variables
     * when canonical ids must match across runtimes.
     *
     * @return the stable identifier for this key within [namespace]
     */
    override fun canonicalId(): String = "$operationName(${variables.canonicalString()})"

    override fun equals(other: Any?): Boolean =
        other is GraphQlOperationKey &&
            other.operationName == operationName &&
            other.variables == variables &&
            other.namespace.value == namespace.value

    override fun hashCode(): Int {
        var result = operationName.hashCode()
        result = 31 * result + variables.hashCode()
        result = 31 * result + namespace.value.hashCode()
        return result
    }

    override fun toString(): String = "GraphQlOperationKey(${namespace.value}/${canonicalId()})"
}
