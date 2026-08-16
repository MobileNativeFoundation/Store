@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.docs

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.StoreResults

private fun signalConflict(
    serverMeta: StoreMeta?,
    message: String,
    cause: Throwable?,
): Nothing {
    // docs:snippet:mutations-server-conflict-signal
    throw StoreResults.exception(
        StoreResults.conflict(serverMeta, message),
        cause,
    )
    // docs:snippet:end
}
