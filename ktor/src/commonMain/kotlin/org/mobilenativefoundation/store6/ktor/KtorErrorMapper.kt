package org.mobilenativefoundation.store6.ktor

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Maps a completed exchange to an outcome; return Defer to keep the default mapping.
 *
 * The mapper runs on every completed response before decode. A result other than
 * [KtorOutcome.Defer] wins. Otherwise the kit applies its default HTTP-status table, and only
 * that default 2xx branch calls decode.
 */
@ExperimentalStoreApi
public fun interface KtorErrorMapper {
    public fun map(exchange: KtorExchange): KtorOutcome

    public companion object {
        /** Always returns [KtorOutcome.Defer]. */
        @ExperimentalStoreApi
        public val Default: KtorErrorMapper = KtorErrorMapper { KtorOutcome.Defer }
    }
}
