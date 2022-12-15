package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.ItemValidator

internal class RealItemValidator<CommonRepresentation : Any>(
    private val realValidator: suspend (item: CommonRepresentation) -> Boolean
) : ItemValidator<CommonRepresentation> {
    override suspend fun isValid(item: CommonRepresentation): Boolean = realValidator(item)
}
