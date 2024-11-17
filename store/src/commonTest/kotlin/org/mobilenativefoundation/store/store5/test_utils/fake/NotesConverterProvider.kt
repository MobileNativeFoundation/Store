package org.mobilenativefoundation.store.store5.test_utils.fake

import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.test_utils.model.InputNote
import org.mobilenativefoundation.store.store5.test_utils.model.NetworkNote
import org.mobilenativefoundation.store.store5.test_utils.model.OutputNote

internal class NotesConverterProvider {
    fun provide(): Converter<NetworkNote, InputNote, OutputNote> =
        Converter.Builder<NetworkNote, InputNote, OutputNote>()
            .fromOutputToLocal { value -> InputNote(data = value.data, ttl = value.ttl) }
            .fromNetworkToLocal { value: NetworkNote ->
                InputNote(
                    data = value.data,
                    ttl = value.ttl ?: inHours(12),
                )
            }
            .build()
}
