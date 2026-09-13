package app.cash.turbine

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val SQLDELIGHT_BORROWED_TIMEOUT = 30.seconds

internal object SqlDelightBorrowedTimeoutMarker

/** Supplies the same timeout context to Turbine's member `testIn` extension. */
@JvmName("sqlDelightBorrowedTurbineScope")
internal suspend fun turbineScope(validate: suspend TurbineContext.() -> Unit) {
    turbineScope(timeout = SQLDELIGHT_BORROWED_TIMEOUT, validate = validate)
}

/**
 * Gives byte-identical borrowed Store-result scenarios a bounded real-driver timeout. SQLDelight
 * query work uses non-test dispatchers, so Turbine's three-second wall-clock default is too narrow
 * when a hosted build is compiling and testing multiple KMP targets.
 */
internal suspend fun <V : Any> Flow<StoreResult<V>>.test(
    timeout: Duration? = null,
    name: String? = null,
    @Suppress("UNUSED_PARAMETER")
    marker: SqlDelightBorrowedTimeoutMarker = SqlDelightBorrowedTimeoutMarker,
    validate: suspend TurbineTestContext<StoreResult<V>>.() -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    val upstream = this as Flow<Any?>
    upstream.test(timeout = timeout ?: SQLDELIGHT_BORROWED_TIMEOUT, name = name) {
        @Suppress("UNCHECKED_CAST")
        validate(this as TurbineTestContext<StoreResult<V>>)
    }
}
