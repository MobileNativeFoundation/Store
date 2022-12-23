package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.SOTNote

internal class NotesConverterProvider {
    fun provide(): Converter<NetworkNote, CommonNote, SOTNote> = Converter.Builder<NetworkNote, CommonNote, SOTNote>()
        .fromSOTToCommon { sot -> CommonNote(data = sot.data, ttl = sot.ttl) }
        .fromCommonToSOT { common -> SOTNote(data = common.data, ttl = common.ttl ?: inHours(12)) }
        .fromNetworkToCommon { network -> CommonNote(data = network.data, ttl = network.ttl) }
        .build()
}
