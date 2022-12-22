package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.util.model.CommonNote

internal class NotesValidator(private val expiration: Long = now()) : Validator<CommonNote> {
    override suspend fun isValid(item: CommonNote?): Boolean = when {
        item == null -> false
        item.ttl == null -> true
        else -> item.ttl > expiration
    }
}