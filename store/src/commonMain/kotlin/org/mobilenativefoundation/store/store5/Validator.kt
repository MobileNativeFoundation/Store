package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealValidator

/**
 * Enables custom validation of [Store] items.
 * @see [StoreReadRequest]
 */
interface Validator<Common : Any> {
    /**
     * Determines whether a [Store] item is valid.
     * If invalid, [MutableStore] will get the latest network value using [Fetcher].
     * [MutableStore] will not validate network responses.
     */
    suspend fun isValid(item: Common): Boolean

    companion object {
        fun <Common : Any> by(
            validator: suspend (item: Common) -> Boolean
        ): Validator<Common> = RealValidator(validator)
    }
}
