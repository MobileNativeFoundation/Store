package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.util.model.InputNote
import org.mobilenativefoundation.store.store5.util.model.OutputNote

internal class NotesValidator(private val expiration: Long = now()) : Validator<OutputNote> {
    override suspend fun isValid(item: OutputNote): Boolean = when {
        item.ttl == null -> true
        else -> item.ttl > expiration
    }
}
