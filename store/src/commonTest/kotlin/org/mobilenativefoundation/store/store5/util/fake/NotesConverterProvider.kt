package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.SOTNote

internal class NotesConverterProvider {
    fun provide(): Converter<NetworkNote, CommonNote, SOTNote> = Converter.Builder<NetworkNote, CommonNote, SOTNote>()
        .fromOutputToLocal { value -> SOTNote(data = value.data, ttl = value.ttl ?: inHours(12)) }
        .fromNetworkToOutput { value: NetworkNote -> SOTNote(data = value.data, ttl = value.ttl?: inHours(12)) }
        .build()
}
