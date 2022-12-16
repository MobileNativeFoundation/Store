package org.mobilenativefoundation.store.store5.market.data.fake

import org.mobilenativefoundation.store.store5.market.data.Api
import org.mobilenativefoundation.store.store5.market.data.model.MarketData
import org.mobilenativefoundation.store.store5.market.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.market.data.model.NoteNetworkRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteNetworkWriteResponse

internal class FakeComplexApi : Api<NoteMarketKey, NoteNetworkRepresentation, NoteCommonRepresentation, NoteNetworkWriteResponse> {
    override val data = mutableMapOf<NoteMarketKey, NoteNetworkRepresentation>()

    init {
        reset()
    }

    override fun get(key: NoteMarketKey, fail: Boolean): NoteNetworkRepresentation? {
        if (fail) {
            throw Exception()
        }

        return data[key]
    }

    override fun post(key: NoteMarketKey, value: NoteCommonRepresentation, fail: Boolean): NoteNetworkWriteResponse {
        if (fail) {
            throw Exception()
        }

        data[key] = NoteNetworkRepresentation(value.data)
        return when (value.data) {
            is MarketData.Collection -> NoteNetworkWriteResponse("${value.data.items.first().id}-${value.data.items.last().id}", true)
            is MarketData.Single -> NoteNetworkWriteResponse(value.data.item.id, true)
            null -> NoteNetworkWriteResponse(null, false)
        }
    }

    fun reset() {
        with(FakeComplexNotes.GetById) {
            data[One.key] = One.network
            data[Two.key] = Two.network
            data[Three.key] = Three.network
            data[Four.key] = Four.network
            data[Five.key] = Five.network
            data[Six.key] = Six.network
            data[Seven.key] = Seven.network
            data[Eight.key] = Eight.network
            data[Nine.key] = Nine.network
            data[Ten.key] = Ten.network
            data[Eleven.key] = Eleven.network
            data[Twelve.key] = Twelve.network
        }

        with(FakeComplexNotes.Paginate) {
            data[First.key] = First.network
            data[Second.key] = Second.network
            data[Third.key] = Third.network
            data[Fourth.key] = Fourth.network
        }
    }
}
