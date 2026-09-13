package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.TaskPayload
import kotlinx.serialization.Serializable
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** Serialized activation payload containing the coordinator registration name. */
@Serializable
@ExperimentalStoreApi
public class StoreDrainPayload(
    @ExperimentalStoreApi
    public val storeName: String,
) : TaskPayload
