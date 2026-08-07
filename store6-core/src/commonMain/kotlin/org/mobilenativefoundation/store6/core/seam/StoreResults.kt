package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.time.Duration

/**
 * StoreResults is the sanctioned construction door for extensions, fakes, and tests; internal constructors remain internal.
 */
@ExperimentalStoreApi
public object StoreResults {
    public fun loading(): StoreResult.Loading = StoreResult.Loading()
    public fun <V> data(value: V, origin: Origin, age: Duration, isStale: Boolean, refreshing: Boolean): StoreResult.Data<V> = StoreResult.Data(value, origin, age, isStale, refreshing)
    public fun revalidated(age: Duration): StoreResult.Revalidated = StoreResult.Revalidated(age)
    public fun error(error: StoreError, servedStale: Boolean): StoreResult.Error = StoreResult.Error(error, servedStale)
    public fun exception(error: StoreError, cause: Throwable? = null): StoreException = StoreException(error, cause)
    public fun fetchError(message: String, cause: Throwable? = null): StoreError.Fetch = StoreError.Fetch(message, cause)
    public fun persistenceError(message: String, cause: Throwable? = null): StoreError.Persistence = StoreError.Persistence(message, cause)
    public fun conversionError(message: String, cause: Throwable? = null): StoreError.Conversion = StoreError.Conversion(message, cause)
    public fun freshnessUnsatisfiable(message: String): StoreError.FreshnessUnsatisfiable = StoreError.FreshnessUnsatisfiable(message)
    public fun conflict(serverMeta: StoreMeta?, message: String): StoreError.Conflict = StoreError.Conflict(serverMeta, message)
    public fun missing(key: StoreKey, message: String): StoreError.Missing = StoreError.Missing(key, message)
}
