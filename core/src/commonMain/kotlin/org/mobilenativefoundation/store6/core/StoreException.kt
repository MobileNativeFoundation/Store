package org.mobilenativefoundation.store6.core

/**
 * Thrown by value-returning [Store] operations when no value can be returned.
 *
 * @param cause the underlying failure, or `null` when no cause is available
 */
public class StoreException internal constructor(
    /** The structured store error represented by this exception. */
    public val error: StoreError,
    cause: Throwable? = null,
) : RuntimeException(messageOf(error), cause)

private fun messageOf(error: StoreError): String =
    when (error) {
        is StoreError.Fetch -> error.message
        is StoreError.Persistence -> error.message
        is StoreError.Conversion -> error.message
        is StoreError.FreshnessUnsatisfiable -> error.message
        is StoreError.Conflict -> error.message
        is StoreError.Missing -> error.message
    }
