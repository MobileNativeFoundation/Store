package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealGoodValidator

/**
 * Enables custom validation of [Store] goods.
 * @see [MarketReader]
 */
interface GoodValidator<Good : Any> {
    /**
     * Determines whether a [Store] good is valid.
     * If invalid, [Market] will get the latest network value using [NetworkFetcher].
     * [Market] will not validate network responses.
     */
    suspend fun isValid(good: Good): Boolean

    companion object {
        fun <Good : Any> by(
            validator: suspend (good: Good) -> Boolean
        ): GoodValidator<Good> = RealGoodValidator(validator)
    }
}