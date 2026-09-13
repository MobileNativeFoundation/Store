@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreResult

/** Everything the demo screen shows, derived purely from (current result, retained data). */
data class DemoUiState<V>(
    /** Content card: the current Data, else the last Data retained across refresh/error. */
    val card: StoreResult.Data<V>?,
    /** Spinner over content: refreshing Data, or Loading while retained content exists. */
    val showSpinner: Boolean,
    /** STALE badge on the card. */
    val showStaleBadge: Boolean,
    /** Error banner over retained content; null when no error or no content to banner over. */
    val errorBanner: String?,
    /** Error with no local value at all (full-surface error state). */
    val emptyError: StoreError?,
    /** Initial Loading with nothing to show yet. */
    val showLoadingPlaceholder: Boolean,
)

@Suppress("UNCHECKED_CAST")
fun <V> deriveDemoUiState(
    current: StoreResult<V>,
    previousData: StoreResult.Data<V>?,
): DemoUiState<V> {
    val card = (current as? StoreResult.Data<V>) ?: previousData
    return DemoUiState(
        card = card,
        showSpinner = (current is StoreResult.Data<*> && current.refreshing) ||
            (current is StoreResult.Loading && card != null),
        showStaleBadge = card?.isStale == true,
        errorBanner = when {
            current !is StoreResult.Error || card == null -> null
            current.servedStale -> "Refresh failed — showing stale data"
            else -> "Refresh failed"
        },
        emptyError = if (current is StoreResult.Error && card == null) current.error else null,
        showLoadingPlaceholder = current is StoreResult.Loading && card == null,
    )
}
