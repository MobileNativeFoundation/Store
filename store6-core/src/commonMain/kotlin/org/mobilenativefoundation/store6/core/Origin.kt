package org.mobilenativefoundation.store6.core

/** Identifies the source from which a [StoreResult.Data] value was obtained. */
public enum class Origin {
    /** The value was served from in-memory resident state. */
    MEMORY,

    /** The value was read from the store's persistent source of truth. */
    SOT,

    /** The value was produced by the store's configured fetcher. */
    FETCHER,

    /** The value reflects an overlay applied above stored data. */
    OVERLAY,
}
