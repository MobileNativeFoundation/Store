package org.mobilenativefoundation.store6.compose.docs

// docs:snippet:mutations-pending-write-ui-badges
import androidx.compose.runtime.Composable
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult

@Composable
fun <V> WriteBadges(
    result: StoreResult<V>,
    saving: @Composable () -> Unit,
    stale: @Composable () -> Unit,
) {
    when (result) {
        is StoreResult.Data -> {
            if (result.origin == Origin.OVERLAY) saving()
            if (result.isStale) stale()
        }
        else -> Unit
    }
}
// docs:snippet:end
