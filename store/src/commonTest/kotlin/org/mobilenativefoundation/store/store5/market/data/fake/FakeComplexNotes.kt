package org.mobilenativefoundation.store.store5.market.data.fake

import org.mobilenativefoundation.store.store5.market.data.model.ComplexNoteRepresentations
import org.mobilenativefoundation.store.store5.market.data.model.MarketData
import org.mobilenativefoundation.store.store5.market.data.model.Note
import org.mobilenativefoundation.store.store5.market.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.market.data.model.NoteNetworkRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteStoreDatabaseRepresentation

internal object FakeComplexNotes {
    const val USER_ID = "12345"
    object Notes {
        val One = Note("1", "TITLE-1", "CONTENT-1")
        val Two = Note("2", "TITLE-2", "CONTENT-2")
        val Three = Note("3", "TITLE-3", "CONTENT-3")
        val Four = Note("4", "TITLE-4", "CONTENT-4")
        val Five = Note("5", "TITLE-5", "CONTENT-5")
        val Six = Note("6", "TITLE-6", "CONTENT-6")
        val Seven = Note("7", "TITLE-7", "CONTENT-7")
        val Eight = Note("8", "TITLE-8", "CONTENT-8")
        val Nine = Note("9", "TITLE-9", "CONTENT-9")
        val Ten = Note("10", "TITLE-10", "CONTENT-10")
        val Eleven = Note("11", "TITLE-11", "CONTENT-11")
        val Twelve = Note("12", "TITLE-12", "CONTENT-12")
    }

    object GetById {
        val One = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.One.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.One)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.One)),
            NoteCommonRepresentation(MarketData.Single(Notes.One)),
        )
        val Two = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Two.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Two)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Two)),
            NoteCommonRepresentation(MarketData.Single(Notes.Two)),
        )
        val Three = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Three.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Three)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Three)),
            NoteCommonRepresentation(MarketData.Single(Notes.Three)),
        )
        val Four = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Four.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Four)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Four)),
            NoteCommonRepresentation(MarketData.Single(Notes.Four)),
        )
        val Five = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Five.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Five)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Five)),
            NoteCommonRepresentation(MarketData.Single(Notes.Five)),
        )
        val Six = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Six.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Six)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Six)),
            NoteCommonRepresentation(MarketData.Single(Notes.Six)),
        )
        val Seven = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Seven.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Seven)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Seven)),
            NoteCommonRepresentation(MarketData.Single(Notes.Seven)),
        )
        val Eight = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Eight.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Eight)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Eight)),
            NoteCommonRepresentation(MarketData.Single(Notes.Eight)),
        )
        val Nine = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Nine.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Nine)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Nine)),
            NoteCommonRepresentation(MarketData.Single(Notes.Nine)),
        )
        val Ten = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Ten.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Ten)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Ten)),
            NoteCommonRepresentation(MarketData.Single(Notes.Ten)),
        )
        val Eleven = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Eleven.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Eleven)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Eleven)),
            NoteCommonRepresentation(MarketData.Single(Notes.Eleven)),
        )
        val Twelve = ComplexNoteRepresentations(
            NoteMarketKey.Read.GetById(Notes.Twelve.id),
            NoteNetworkRepresentation(MarketData.Single(Notes.Twelve)),
            NoteStoreDatabaseRepresentation(MarketData.Single(Notes.Twelve)),
            NoteCommonRepresentation(MarketData.Single(Notes.Twelve)),
        )
    }

    object Paginate {
        val First = ComplexNoteRepresentations(
            NoteMarketKey.Read.Paginate(0, 3),
            NoteNetworkRepresentation(MarketData.Collection(listOf(Notes.One, Notes.Two, Notes.Three))),
            NoteStoreDatabaseRepresentation(MarketData.Collection(listOf(Notes.One, Notes.Two, Notes.Three))),
            NoteCommonRepresentation(MarketData.Collection(listOf(Notes.One, Notes.Two, Notes.Three))),
        )
        val Second = ComplexNoteRepresentations(
            NoteMarketKey.Read.Paginate(3, 3),
            NoteNetworkRepresentation(MarketData.Collection(listOf(Notes.Four, Notes.Five, Notes.Six))),
            NoteStoreDatabaseRepresentation(MarketData.Collection(listOf(Notes.Four, Notes.Five, Notes.Six))),
            NoteCommonRepresentation(MarketData.Collection(listOf(Notes.Four, Notes.Five, Notes.Six))),
        )
        val Third = ComplexNoteRepresentations(
            NoteMarketKey.Read.Paginate(6, 3),
            NoteNetworkRepresentation(MarketData.Collection(listOf(Notes.Seven, Notes.Eight, Notes.Nine))),
            NoteStoreDatabaseRepresentation(MarketData.Collection(listOf(Notes.Seven, Notes.Eight, Notes.Nine))),
            NoteCommonRepresentation(MarketData.Collection(listOf(Notes.Seven, Notes.Eight, Notes.Nine))),
        )
        val Fourth = ComplexNoteRepresentations(
            NoteMarketKey.Read.Paginate(9, 3),
            NoteNetworkRepresentation(MarketData.Collection(listOf(Notes.Ten, Notes.Eleven, Notes.Twelve))),
            NoteStoreDatabaseRepresentation(MarketData.Collection(listOf(Notes.Ten, Notes.Eleven, Notes.Twelve))),
            NoteCommonRepresentation(MarketData.Collection(listOf(Notes.Ten, Notes.Eleven, Notes.Twelve))),
        )
    }

    fun list() = with(Notes) {
        listOf(One, Two, Three, Four, Five, Six, Seven, Eight, Nine, Ten, Eleven)
    }

    fun listN(n: Int) = list().subList(0, n)
}
