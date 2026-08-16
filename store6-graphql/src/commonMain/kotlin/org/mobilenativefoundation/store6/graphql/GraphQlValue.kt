package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * A GraphQL input value used as an operation variable.
 *
 * The hierarchy mirrors the GraphQL input value grammar without depending on a serialization
 * library: the caller's [GraphQlExecutor] owns the wire encoding, while Store uses these values
 * only to derive stable cache identity. Two values are equal when they are structurally equal;
 * [ObjectValue] fields compare independently of insertion order and [ListValue] entries compare
 * in order.
 */
@ExperimentalStoreApi
public sealed interface GraphQlValue {
    /** The explicit GraphQL `null` literal, distinct from an absent variable. */
    public data object NullValue : GraphQlValue

    /** A GraphQL `Boolean` value. */
    public class BooleanValue(
        /** The wrapped boolean. */
        public val value: Boolean,
    ) : GraphQlValue {
        override fun equals(other: Any?): Boolean = other is BooleanValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "BooleanValue($value)"
    }

    /**
     * A GraphQL `Int` value, widened to [Long] so 64-bit identifiers keep exact identity.
     *
     * [IntValue] and [FloatValue] are never equal, matching the GraphQL distinction between
     * `Int` and `Float` inputs.
     */
    public class IntValue(
        /** The wrapped integer. */
        public val value: Long,
    ) : GraphQlValue {
        override fun equals(other: Any?): Boolean = other is IntValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "IntValue($value)"
    }

    /**
     * A GraphQL `Float` value.
     *
     * Canonical rendering uses the runtime's `Double.toString`, which differs across Kotlin
     * targets (JS drops the trailing `.0` of whole numbers). Prefer [IntValue] or [StringValue]
     * variables when canonical ids must match across runtimes, for example in a shared
     * persistent cache.
     */
    public class FloatValue(
        /** The wrapped floating-point number. */
        public val value: Double,
    ) : GraphQlValue {
        override fun equals(other: Any?): Boolean = other is FloatValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "FloatValue($value)"
    }

    /** A GraphQL `String` value. */
    public class StringValue(
        /** The wrapped string. */
        public val value: String,
    ) : GraphQlValue {
        override fun equals(other: Any?): Boolean = other is StringValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "StringValue($value)"
    }

    /** A GraphQL list value; entry order is significant for equality and cache identity. */
    public class ListValue(
        values: List<GraphQlValue>,
    ) : GraphQlValue {
        /** The entries in declaration order. */
        public val values: List<GraphQlValue> = values.toList()

        override fun equals(other: Any?): Boolean = other is ListValue && other.values == values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "ListValue($values)"
    }

    /** A GraphQL input object value; field order is irrelevant for equality and cache identity. */
    public class ObjectValue(
        fields: Map<String, GraphQlValue>,
    ) : GraphQlValue {
        /** The fields of this object. */
        public val fields: Map<String, GraphQlValue> = fields.toMap()

        override fun equals(other: Any?): Boolean = other is ObjectValue && other.fields == fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "ObjectValue($fields)"
    }
}

/**
 * The named variables of one GraphQL operation execution.
 *
 * Variables are structural: two instances with equal entry maps are equal regardless of
 * insertion order, and they render to the same canonical form in
 * [GraphQlOperationKey.canonicalId]. Build instances with [graphQlVariables] or wrap an
 * existing map directly.
 */
@ExperimentalStoreApi
public class GraphQlVariables(
    entries: Map<String, GraphQlValue>,
) {
    /** The variable bindings by name. */
    public val entries: Map<String, GraphQlValue> = entries.toMap()

    override fun equals(other: Any?): Boolean = other is GraphQlVariables && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "GraphQlVariables(${canonicalString()})"

    public companion object {
        /** The empty variable set, rendered canonically as `{}`. */
        public val Empty: GraphQlVariables = GraphQlVariables(emptyMap())
    }
}

/**
 * Builds a [GraphQlVariables] instance.
 *
 * @param build configuration applied to a fresh [GraphQlObjectBuilder]
 * @return the built variables
 */
@ExperimentalStoreApi
public fun graphQlVariables(build: GraphQlObjectBuilder.() -> Unit): GraphQlVariables =
    GraphQlVariables(GraphQlObjectBuilder().apply(build).buildFields())

/**
 * Builder receiver for GraphQL object structures: the top level of [graphQlVariables] and
 * nested [GraphQlValue.ObjectValue] fields.
 *
 * A repeated `put` for one name replaces the earlier binding.
 */
@ExperimentalStoreApi
public class GraphQlObjectBuilder internal constructor() {
    private val fields = LinkedHashMap<String, GraphQlValue>()

    /** Binds [name] to [value]. */
    public fun put(
        name: String,
        value: GraphQlValue,
    ) {
        fields[name] = value
    }

    /** Binds [name] to a [GraphQlValue.StringValue]. */
    public fun put(
        name: String,
        value: String,
    ) {
        put(name, GraphQlValue.StringValue(value))
    }

    /** Binds [name] to a [GraphQlValue.IntValue]. */
    public fun put(
        name: String,
        value: Int,
    ) {
        put(name, GraphQlValue.IntValue(value.toLong()))
    }

    /** Binds [name] to a [GraphQlValue.IntValue]. */
    public fun put(
        name: String,
        value: Long,
    ) {
        put(name, GraphQlValue.IntValue(value))
    }

    /** Binds [name] to a [GraphQlValue.FloatValue]; see its cross-runtime rendering caveat. */
    public fun put(
        name: String,
        value: Double,
    ) {
        put(name, GraphQlValue.FloatValue(value))
    }

    /** Binds [name] to a [GraphQlValue.BooleanValue]. */
    public fun put(
        name: String,
        value: Boolean,
    ) {
        put(name, GraphQlValue.BooleanValue(value))
    }

    /** Binds [name] to the explicit GraphQL `null` literal. */
    public fun putNull(name: String) {
        put(name, GraphQlValue.NullValue)
    }

    /** Binds [name] to a nested [GraphQlValue.ObjectValue] configured by [build]. */
    public fun putObject(
        name: String,
        build: GraphQlObjectBuilder.() -> Unit,
    ) {
        put(name, GraphQlValue.ObjectValue(GraphQlObjectBuilder().apply(build).buildFields()))
    }

    /** Binds [name] to a nested [GraphQlValue.ListValue] configured by [build]. */
    public fun putList(
        name: String,
        build: GraphQlListBuilder.() -> Unit,
    ) {
        put(name, GraphQlValue.ListValue(GraphQlListBuilder().apply(build).buildValues()))
    }

    internal fun buildFields(): Map<String, GraphQlValue> = fields.toMap()
}

/** Builder receiver for [GraphQlValue.ListValue] entries; entries keep insertion order. */
@ExperimentalStoreApi
public class GraphQlListBuilder internal constructor() {
    private val values = mutableListOf<GraphQlValue>()

    /** Appends [value]. */
    public fun add(value: GraphQlValue) {
        values.add(value)
    }

    /** Appends a [GraphQlValue.StringValue]. */
    public fun add(value: String) {
        add(GraphQlValue.StringValue(value))
    }

    /** Appends a [GraphQlValue.IntValue]. */
    public fun add(value: Int) {
        add(GraphQlValue.IntValue(value.toLong()))
    }

    /** Appends a [GraphQlValue.IntValue]. */
    public fun add(value: Long) {
        add(GraphQlValue.IntValue(value))
    }

    /** Appends a [GraphQlValue.FloatValue]; see its cross-runtime rendering caveat. */
    public fun add(value: Double) {
        add(GraphQlValue.FloatValue(value))
    }

    /** Appends a [GraphQlValue.BooleanValue]. */
    public fun add(value: Boolean) {
        add(GraphQlValue.BooleanValue(value))
    }

    /** Appends the explicit GraphQL `null` literal. */
    public fun addNull() {
        add(GraphQlValue.NullValue)
    }

    /** Appends a nested [GraphQlValue.ObjectValue] configured by [build]. */
    public fun addObject(build: GraphQlObjectBuilder.() -> Unit) {
        add(GraphQlValue.ObjectValue(GraphQlObjectBuilder().apply(build).buildFields()))
    }

    /** Appends a nested [GraphQlValue.ListValue] configured by [build]. */
    public fun addList(build: GraphQlListBuilder.() -> Unit) {
        add(GraphQlValue.ListValue(GraphQlListBuilder().apply(build).buildValues()))
    }

    internal fun buildValues(): List<GraphQlValue> = values.toList()
}
