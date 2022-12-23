package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Validator

internal class RealValidator<Output : Any>(
    private val realValidator: suspend (item: Output) -> Boolean
) : Validator<Output> {
    override suspend fun isValid(item: Output): Boolean = realValidator(item)
}
