package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreMeta

/** Constructible [StoreMeta] for consumer assertions and error payloads. */
@ExperimentalStoreApi
public class TestStoreMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String? = null,
) : StoreMeta
