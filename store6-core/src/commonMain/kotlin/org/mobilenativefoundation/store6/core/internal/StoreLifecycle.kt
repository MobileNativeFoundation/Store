package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException

/** Stable diagnostic used when an operation is attempted after store closure. */
internal const val STORE_CLOSED_MESSAGE: String = "Store is closed."

/** Diagnostic for the internal cancellation that destroys an evicted quiescent engine. */
internal const val ENGINE_EVICTED_MESSAGE: String = "Engine evicted after quiescence."

/** Default bound on quiescent engine residency (StoreBuilder.maxIdleKeys). */
internal const val DEFAULT_MAX_IDLE_ENGINES: Int = 128

/** Grace period the shared reader pipeline stays subscribed after its last collector leaves. */
internal const val READER_PIPELINE_GRACE_MILLIS: Long = 100L

/** Fixed defensive delay before retrying a failed reader subscription. */
internal const val READER_RETRY_DELAY_MILLIS: Long = 100L

/** Creates the deterministic failure for an operation that requires an open store. */
internal fun storeClosedException(): IllegalStateException =
    IllegalStateException(STORE_CLOSED_MESSAGE)

/** Creates the cancellation used to terminate work that was active when the store closed. */
internal fun storeClosedCancellation(): CancellationException =
    CancellationException(STORE_CLOSED_MESSAGE)
