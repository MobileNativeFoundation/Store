package org.mobilenativefoundation.store6.core

/**
 * Identifies a value handled by a [Store].
 *
 * A key's [namespace] and [canonicalId] together form its stable identity.
 * Implementations should return the same identity components for the lifetime of the key.
 */
public interface StoreKey {
    /** The logical key space containing this key. */
    public val namespace: StoreNamespace

    /**
     * Returns the stable identifier for this key within [namespace].
     *
     * @return an identifier that is unique within the key's namespace
     */
    public fun canonicalId(): String
}

/** A logical key space used to distinguish otherwise identical canonical identifiers. */
public class StoreNamespace(
    /** The stable name of this namespace. */
    public val value: String,
)
