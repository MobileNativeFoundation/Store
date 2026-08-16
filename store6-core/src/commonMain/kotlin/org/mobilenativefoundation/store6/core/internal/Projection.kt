package org.mobilenativefoundation.store6.core.internal

/** The private overlay projection vocabulary retained by the engine writer. */
internal sealed interface Projection<out V : Any> {
    /** Pass-through intent; collector-local authorization supplies the render context. */
    data class Value<V : Any>(
        val envelope: ValueEnvelope<V>,
    ) : Projection<V>

    /** A value created or changed by the overlay. */
    data class Overlaid<V : Any>(
        val value: V,
    ) : Projection<V>

    /** Projected absence. */
    data object Absent : Projection<Nothing>
}

/** Deterministic failure surfaced by every configured stream after projection terminalization. */
internal class OverlayProjectionException(
    cause: Throwable,
) : IllegalStateException(
        "Overlay projection failed for this key; apply and changes are no-failure contracts.",
        (cause as? OverlayProjectionException)?.cause ?: cause,
    )
