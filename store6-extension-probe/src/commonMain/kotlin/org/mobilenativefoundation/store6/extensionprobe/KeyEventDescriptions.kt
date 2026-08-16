package org.mobilenativefoundation.store6.extensionprobe

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.KeyEvents

/**
 * Describes an advisory event without assuming that the open hierarchy is exhaustive.
 *
 * The mandatory `else` keeps consumers source-compatible with variants added in minor releases.
 */
@OptIn(ExperimentalStoreApi::class)
// docs:snippet:guides-extending-key-events
public fun describeKeyEvent(event: KeyEvents): String =
    when (event) {
        is KeyEvents.Written -> "written(${event.key.canonicalId()}, ${event.origin})"
        is KeyEvents.Invalidated -> "invalidated(${event.key.canonicalId()})"
        is KeyEvents.Deleted -> "deleted(${event.key.canonicalId()})"
        else -> "unknown(${event.key.canonicalId()})"
    }
// docs:snippet:end
