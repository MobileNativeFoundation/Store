package org.mobilenativefoundation.store.store5.data.fake

import org.mobilenativefoundation.store.store5.data.model.ComplexNetworkNoteEntry
import org.mobilenativefoundation.store.store5.data.model.ComplexNoteEntry
import org.mobilenativefoundation.store.store5.data.model.MarketData
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

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
        val One = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.One.id), NoteMarketOutput.Read(MarketData.Single(Notes.One)))
        val Two = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Two.id), NoteMarketOutput.Read(MarketData.Single(Notes.Two)))
        val Three = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Three.id), NoteMarketOutput.Read(MarketData.Single(Notes.Three)))
        val Four = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Four.id), NoteMarketOutput.Read(MarketData.Single(Notes.Four)))
        val Five = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Five.id), NoteMarketOutput.Read(MarketData.Single(Notes.Five)))
        val Six = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Six.id), NoteMarketOutput.Read(MarketData.Single(Notes.Six)))
        val Seven = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Seven.id), NoteMarketOutput.Read(MarketData.Single(Notes.Seven)))
        val Eight = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Eight.id), NoteMarketOutput.Read(MarketData.Single(Notes.Eight)))
        val Nine = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Nine.id), NoteMarketOutput.Read(MarketData.Single(Notes.Nine)))
        val Ten = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Ten.id), NoteMarketOutput.Read(MarketData.Single(Notes.Ten)))
        val Eleven = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Eleven.id), NoteMarketOutput.Read(MarketData.Single(Notes.Eleven)))
        val Twelve = ComplexNoteEntry(NoteMarketKey.Read.GetById(Notes.Twelve.id), NoteMarketOutput.Read(MarketData.Single(Notes.Twelve)))
    }

    object GetByNetworkId {
        val One = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.One.id), NoteMarketInput(MarketData.Single(Notes.One)))
        val Two = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Two.id), NoteMarketInput(MarketData.Single(Notes.Two)))
        val Three = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Three.id), NoteMarketInput(MarketData.Single(Notes.Three)))
        val Four = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Four.id), NoteMarketInput(MarketData.Single(Notes.Four)))
        val Five = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Five.id), NoteMarketInput(MarketData.Single(Notes.Five)))
        val Six = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Six.id), NoteMarketInput(MarketData.Single(Notes.Six)))
        val Seven = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Seven.id), NoteMarketInput(MarketData.Single(Notes.Seven)))
        val Eight = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Eight.id), NoteMarketInput(MarketData.Single(Notes.Eight)))
        val Nine = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Nine.id), NoteMarketInput(MarketData.Single(Notes.Nine)))
        val Ten = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Ten.id), NoteMarketInput(MarketData.Single(Notes.Ten)))
        val Eleven = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Eleven.id), NoteMarketInput(MarketData.Single(Notes.Eleven)))
        val Twelve = ComplexNetworkNoteEntry(NoteMarketKey.Read.GetById(Notes.Twelve.id), NoteMarketInput(MarketData.Single(Notes.Twelve)))
    }

    object Paginate {
        val First = ComplexNoteEntry(
            NoteMarketKey.Read.Paginate(0, 3), NoteMarketOutput.Read(MarketData.Collection(listOf(Notes.One, Notes.Two, Notes.Three)))
        )

        val Second = ComplexNoteEntry(
            NoteMarketKey.Read.Paginate(3, 3), NoteMarketOutput.Read(MarketData.Collection(listOf(Notes.Four, Notes.Five, Notes.Six)))
        )

        val Third = ComplexNoteEntry(
            NoteMarketKey.Read.Paginate(6, 3), NoteMarketOutput.Read(MarketData.Collection(listOf(Notes.Seven, Notes.Eight, Notes.Nine)))
        )

        val Fourth = ComplexNoteEntry(
            NoteMarketKey.Read.Paginate(9, 3), NoteMarketOutput.Read(MarketData.Collection(listOf(Notes.Ten, Notes.Eleven, Notes.Twelve)))
        )
    }

    object PaginateNetwork {
        val First = ComplexNetworkNoteEntry(
            NoteMarketKey.Read.Paginate(0, 3), NoteMarketInput(MarketData.Collection(listOf(Notes.One, Notes.Two, Notes.Three)))
        )

        val Second = ComplexNetworkNoteEntry(
            NoteMarketKey.Read.Paginate(3, 3), NoteMarketInput(MarketData.Collection(listOf(Notes.Four, Notes.Five, Notes.Six)))
        )

        val Third = ComplexNetworkNoteEntry(
            NoteMarketKey.Read.Paginate(6, 3), NoteMarketInput(MarketData.Collection(listOf(Notes.Seven, Notes.Eight, Notes.Nine)))
        )

        val Fourth = ComplexNetworkNoteEntry(
            NoteMarketKey.Read.Paginate(9, 3), NoteMarketInput(MarketData.Collection(listOf(Notes.Ten, Notes.Eleven, Notes.Twelve)))
        )
    }

    fun list() = with(Notes) {
        listOf(One, Two, Three, Four, Five, Six, Seven, Eight, Nine, Ten, Eleven)
    }

    fun listN(n: Int) = list().subList(0, n)
}
