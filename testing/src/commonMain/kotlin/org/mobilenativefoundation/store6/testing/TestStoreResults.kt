package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.StoreResults
import kotlin.time.Duration

/**
 * Test-facing factories with test-friendly defaults; every member delegates to the seam's
 * [StoreResults] door (the sanctioned construction path; StoreResult, StoreError, and
 * StoreException constructors are internal). Covers all [StoreResult] states and StoreError
 * variants.
 */
@ExperimentalStoreApi
public object TestStoreResults {
    public fun loading(): StoreResult.Loading = StoreResults.loading()

    public fun <V> data(
        value: V,
        origin: Origin = Origin.MEMORY,
        age: Duration = Duration.ZERO,
        isStale: Boolean = false,
        refreshing: Boolean = false,
    ): StoreResult.Data<V> = StoreResults.data(value, origin, age, isStale, refreshing)

    public fun revalidated(age: Duration = Duration.ZERO): StoreResult.Revalidated =
        StoreResults.revalidated(age)

    public fun error(
        error: StoreError,
        servedStale: Boolean = false,
    ): StoreResult.Error = StoreResults.error(error, servedStale)

    public fun exception(
        error: StoreError,
        cause: Throwable? = null,
    ): StoreException = StoreResults.exception(error, cause)

    public fun fetchError(
        message: String,
        cause: Throwable? = null,
    ): StoreError.Fetch = StoreResults.fetchError(message, cause)

    public fun persistenceError(
        message: String,
        cause: Throwable? = null,
    ): StoreError.Persistence = StoreResults.persistenceError(message, cause)

    public fun conversionError(
        message: String,
        cause: Throwable? = null,
    ): StoreError.Conversion = StoreResults.conversionError(message, cause)

    public fun freshnessUnsatisfiable(message: String): StoreError.FreshnessUnsatisfiable =
        StoreResults.freshnessUnsatisfiable(message)

    public fun conflict(
        serverMeta: StoreMeta?,
        message: String,
    ): StoreError.Conflict = StoreResults.conflict(serverMeta, message)

    public fun missing(
        key: StoreKey,
        message: String,
    ): StoreError.Missing = StoreResults.missing(key, message)
}
