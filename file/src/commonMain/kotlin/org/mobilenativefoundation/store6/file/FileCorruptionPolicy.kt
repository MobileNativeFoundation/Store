package org.mobilenativefoundation.store6.file

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

@ExperimentalStoreApi
public enum class FileCorruptionPolicy {
    /** Move the unreadable file aside with a `.corrupt` suffix (best-effort) and treat the row as absent. */
    QUARANTINE,

    /** Throw from the reading operation. Reader collections then follow the engine's retry contract. */
    PROPAGATE,
}
