package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Canonical registry identity for a store key.
 *
 * Keeping namespace and canonical identifier as separate fields avoids relying on a key
 * implementation's equality contract and avoids collisions caused by string concatenation.
 */
internal data class KeyId(
    val namespace: String,
    val canonicalId: String,
) {
    companion object {
        /** Captures the canonical identity exposed by [key]. */
        fun from(key: StoreKey): KeyId =
            KeyId(
                namespace = key.namespace.value,
                canonicalId = key.canonicalId(),
            )
    }
}
