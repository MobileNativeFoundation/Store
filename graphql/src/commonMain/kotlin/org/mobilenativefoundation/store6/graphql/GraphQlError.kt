package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * One entry of a GraphQL response's `errors` array.
 *
 * @property message the server-provided error message
 * @property path the response field path the error applies to; empty for request-level errors
 */
@ExperimentalStoreApi
public class GraphQlError(
    public val message: String,
    public val path: List<PathSegment> = emptyList(),
) {
    /** One step of a response field path. */
    public sealed interface PathSegment {
        /** A field name step. */
        public class Field(
            /** The field name. */
            public val name: String,
        ) : PathSegment {
            override fun equals(other: Any?): Boolean = other is Field && other.name == name

            override fun hashCode(): Int = name.hashCode()

            override fun toString(): String = name
        }

        /** A list index step. */
        public class Index(
            /** The zero-based list index. */
            public val index: Int,
        ) : PathSegment {
            override fun equals(other: Any?): Boolean = other is Index && other.index == index

            override fun hashCode(): Int = index.hashCode()

            override fun toString(): String = "[$index]"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GraphQlError && other.message == message && other.path == path

    override fun hashCode(): Int = 31 * message.hashCode() + path.hashCode()

    override fun toString(): String =
        if (path.isEmpty()) "GraphQlError($message)" else "GraphQlError($message at ${renderPath(path)})"
}

/**
 * The failure carried as [org.mobilenativefoundation.store6.core.StoreError.Fetch] cause when a
 * GraphQL response reports errors the fetcher does not adopt.
 *
 * @property operationName the operation whose response carried the errors
 * @property errors every decoded response error, in response order
 */
@ExperimentalStoreApi
public class GraphQlOperationException(
    public val operationName: String,
    public val errors: List<GraphQlError>,
) : Exception(buildOperationExceptionMessage(operationName, errors))

@OptIn(ExperimentalStoreApi::class)
private fun buildOperationExceptionMessage(
    operationName: String,
    errors: List<GraphQlError>,
): String {
    val first = errors.firstOrNull() ?: return "GraphQL operation '$operationName' failed."
    val location = if (first.path.isEmpty()) "" else " at ${renderPath(first.path)}"
    val remainder = if (errors.size > 1) "; errors carries the remaining ${errors.size - 1}" else ""
    return "GraphQL operation '$operationName' returned ${errors.size} error(s): " +
        "'${first.message}'$location$remainder."
}

@OptIn(ExperimentalStoreApi::class)
private fun renderPath(path: List<GraphQlError.PathSegment>): String =
    buildString {
        path.forEachIndexed { index, segment ->
            if (index > 0 && segment is GraphQlError.PathSegment.Field) append('.')
            append(segment)
        }
    }
