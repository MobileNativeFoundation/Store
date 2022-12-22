package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Validator

internal class RealValidator<Common : Any>(
    private val realValidator: suspend (item: Common) -> Boolean
) : Validator<Common> {
    override suspend fun isValid(item: Common): Boolean = realValidator(item)
}
