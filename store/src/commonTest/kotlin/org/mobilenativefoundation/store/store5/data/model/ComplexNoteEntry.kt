package org.mobilenativefoundation.store.store5.data.model

internal data class ComplexNoteEntry(
    val key: NoteMarketKey,
    val note: NoteMarketOutput
)

internal data class ComplexNetworkNoteEntry(
    val key: NoteMarketKey,
    val note: NoteMarketInput
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

sealed class MarketData<Item : Any> {
    data class Single<Item : Any>(val item: Item) : MarketData<Item>()
    data class Collection<Item : Any>(val items: List<Item>) : MarketData<Item>()
}
