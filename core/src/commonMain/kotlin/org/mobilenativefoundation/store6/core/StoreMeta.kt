package org.mobilenativefoundation.store6.core

/**
 * Typed freshness and identity metadata attached to stored values.
 *
 * [StoreResult.Data.age] and age-bounded [Freshness] policies derive from this metadata; an untyped
 * metadata channel does not exist anywhere in Store. Milliseconds since the Unix epoch are used
 * because no stable cross-platform instant type exists on the current language floor.
 */
public interface StoreMeta {
    /** The wall-clock time at which the value was written, in Unix epoch milliseconds. */
    public val writtenAtEpochMillis: Long

    /** The optional entity tag associated with the value. */
    public val etag: String?
}
