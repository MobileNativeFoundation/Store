package org.mobilenativefoundation.store.store5.data.model

import kotlinx.serialization.Serializable

internal data class ComplexNoteEntry(
    val key: NoteMarketKey,
    val note: NoteMarketOutput
)

internal data class ComplexNoteRepresentations(
    val key: NoteMarketKey,
    val network: NoteNetworkRepresentation,
    val database: NoteStoreDatabaseRepresentation,
    val common: NoteCommonRepresentation
)

internal sealed class NoteMarketKey {
    sealed class Read : NoteMarketKey() {
        data class GetById(val noteId: String) : Read()
        data class Paginate(val startIndex: Int, val size: Int) : Read()
    }

    data class Write(val note: Note) : NoteMarketKey()
}

internal data class NoteMarketInput(
    val data: MarketData<Note>? = null
)

internal sealed class NoteMarketOutput {
    data class Read(val data: MarketData<Note>?) : NoteMarketOutput()
}

@Serializable
sealed class MarketData<Item : Any> {
    @Serializable
    data class Single<Item : Any>(val item: Item) : MarketData<Item>()

    @Serializable
    data class Collection<Item : Any>(val items: List<Item>) : MarketData<Item>()
}

data class NoteNetworkWriteResponse(
    val id: String? = null,
    val ok: Boolean
)

internal data class NoteNetworkRepresentation(
    val data: MarketData<Note>? = null
)

internal data class NoteCommonRepresentation(
    val data: MarketData<Note>? = null
)

@Serializable
internal data class NoteStoreDatabaseRepresentation(
    val data: MarketData<Note>? = null
)
