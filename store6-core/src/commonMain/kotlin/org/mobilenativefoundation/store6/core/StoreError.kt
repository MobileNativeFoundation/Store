package org.mobilenativefoundation.store6.core

/**
 * A structured failure produced by a [Store] operation.
 *
 * The variant set is frozen for the 6.x major: new failure kinds map into these categories via
 * their structured detail payloads, which lets the hierarchy bridge to an exhaustive Swift enum.
 * Every message states what was attempted, for which key or namespace, and the likely fix.
 */
public sealed class StoreError {
    /** Indicates that the configured fetcher failed to produce a value. */
    public class Fetch internal constructor(
        /** What was attempted, for which key, and the likely fix. */
        public val message: String,

        /** The underlying failure, or `null` when no cause is available. */
        public val cause: Throwable?,
    ) : StoreError()

    /** Indicates that a persistence operation against the store's durable state failed. */
    public class Persistence internal constructor(
        /** What was attempted, for which key or namespace, and the likely fix. */
        public val message: String,

        /** The underlying failure, or `null` when no cause is available. */
        public val cause: Throwable?,
    ) : StoreError()

    /** Indicates that a value could not be converted between representations. */
    public class Conversion internal constructor(
        /** What was attempted, for which key, and the likely fix. */
        public val message: String,

        /** The underlying failure, or `null` when no cause is available. */
        public val cause: Throwable?,
    ) : StoreError()

    /** Indicates that the requested [Freshness] policy could not be satisfied. */
    public class FreshnessUnsatisfiable internal constructor(
        /** Which policy failed, for which key, and the likely fix. */
        public val message: String,
    ) : StoreError()

    /** Indicates that a write conflicted with authoritative server state. */
    public class Conflict internal constructor(
        /** Server-side metadata describing the conflicting state, when available. */
        public val serverMeta: StoreMeta?,

        /** What conflicted, for which key, and the likely fix. */
        public val message: String,
    ) : StoreError()

    /** Indicates that no value exists for `key` and none could be produced. */
    public class Missing internal constructor(
        /** The key for which no value exists. */
        public val key: StoreKey,

        /** Why the value is missing and the likely fix. */
        public val message: String,
    ) : StoreError()
}
