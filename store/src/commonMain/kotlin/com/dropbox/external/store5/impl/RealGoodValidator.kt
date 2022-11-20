package com.dropbox.external.store5.impl

import com.dropbox.external.store5.GoodValidator

internal class RealGoodValidator<Good : Any>(
    private val realValidator: suspend (good: Good) -> Boolean
) : GoodValidator<Good> {
    override suspend fun isValid(good: Good): Boolean = realValidator(good)
}

