package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealItemValidator

/**
 * Enables custom validation of [Store] items.
 * @see [MarketReader]
 */
interface ItemValidator<Item : Any> {
    /**
     * Determines whether a [Store] item is valid.
     * If invalid, [Market] will get the latest network value using [NetworkFetcher].
     * [Market] will not validate network responses.
     */
    suspend fun isValid(item: Item): Boolean

    companion object {
        fun <Item : Any> by(
            validator: suspend (item: Item) -> Boolean
        ): ItemValidator<Item> = RealItemValidator(validator)
    }
}
