package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealItemValidator

/**
 * Enables custom validation of [Store] items.
 * @see [ReadRequest]
 */
interface ItemValidator<CommonRepresentation : Any> {
    /**
     * Determines whether a [Store] item is valid.
     * If invalid, [Market] will get the latest network value using [NetworkFetcher].
     * [Market] will not validate network responses.
     */
    suspend fun isValid(item: CommonRepresentation): Boolean

    companion object {
        fun <CommonRepresentation : Any> by(
            validator: suspend (item: CommonRepresentation) -> Boolean
        ): ItemValidator<CommonRepresentation> = RealItemValidator(validator)
    }
}