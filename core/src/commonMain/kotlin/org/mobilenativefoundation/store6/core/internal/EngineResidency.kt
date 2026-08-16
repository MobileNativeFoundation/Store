package org.mobilenativefoundation.store6.core.internal

/**
 * Residency callbacks a [KeyEngine] uses so engine-owned background work pins its own residency.
 *
 * The only engine-owned work that outlives its caller's registry reference is the fetch job
 * (fetch survives waiter cancellation). The job retains one reference as its first act and
 * releases it after settlement, so an engine with an in-flight fetch is unevictable by
 * construction and its release is the trigger for the registry's quiescence check.
 */
internal interface EngineResidencyHooks {
    /** Retains one residency reference; the engine is active because the launcher holds a ref. */
    suspend fun retainFetchRef()

    /** Releases the fetch reference and runs the idle/eviction check at zero references. */
    suspend fun releaseFetchRef()

    /** No-op hooks for direct engine tests that construct a [KeyEngine] without a registry. */
    object Noop : EngineResidencyHooks {
        override suspend fun retainFetchRef() = Unit

        override suspend fun releaseFetchRef() = Unit
    }
}
