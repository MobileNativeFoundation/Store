package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.ItemValidator

internal class RealItemValidator<Item : Any>(
    private val realValidator: suspend (item: Item) -> Boolean
) : ItemValidator<Item> {
    override suspend fun isValid(item: Item): Boolean = realValidator(item)
}
