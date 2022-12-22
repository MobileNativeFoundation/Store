package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.fake.NotesApi
import org.mobilenativefoundation.store.store5.util.fake.NotesBookkeeping
import org.mobilenativefoundation.store.store5.util.fake.NotesConverterProvider
import org.mobilenativefoundation.store.store5.util.fake.NotesDatabase
import org.mobilenativefoundation.store.store5.util.fake.NotesUpdaterProvider
import org.mobilenativefoundation.store.store5.util.fake.NotesValidator
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse
import org.mobilenativefoundation.store.store5.util.model.SOTNote
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class UpdaterTests {
    private val testScope = TestScope()
    private lateinit var api: NotesApi
    private lateinit var bookkeeping: NotesBookkeeping
    private lateinit var notes: NotesDatabase

    @BeforeTest
    fun before() {
        api = NotesApi()
        bookkeeping = NotesBookkeeping()
        notes = NotesDatabase()
    }

    @Test
    fun givenEmptyMarketWhenWriteThenSuccessResponsesAndApiUpdated() = testScope.runTest {
        val converter = NotesConverterProvider().provide()
        val validator = NotesValidator()
        val updater = NotesUpdaterProvider(api).provide()
        val bookkeeper = Bookkeeper.by(
            getLastFailedSync = bookkeeping::getLastFailedSync,
            setLastFailedSync = bookkeeping::setLastFailedSync,
            clear = bookkeeping::clear,
            clearAll = bookkeeping::clear
        )

        val store = StoreBuilder.from<String, NetworkNote, CommonNote, SOTNote>(
            fetcher = Fetcher.ofFlow { key ->
                val network = NetworkNote(NoteData.Single(Note("$key-id", "$key-title", "$key-content")))
                flow { emit(network) }
            },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> notes.get(key) },
                writer = { key, sot -> notes.put(key, sot) },
                delete = { key -> notes.clear(key) },
                deleteAll = { notes.clear() }
            )
        )
            .converter(converter)
            .validator(validator)
            .build(
                updater = updater,
                bookkeeper = bookkeeper
            )

        val noteKey = "1-id"
        val noteTitle = "1-title"
        val noteContent = "1-content"
        val noteData = NoteData.Single(Note(noteKey, noteTitle, noteContent))
        val writeRequest = StoreWriteRequest.of<String, CommonNote, NotesWriteResponse>(
            key = noteKey,
            input = CommonNote(noteData)
        )

        val storeWriteResponse = store.write(writeRequest)

        assertEquals(StoreWriteResponse.Success.Typed(NotesWriteResponse(noteKey, true)), storeWriteResponse)
        assertEquals(NetworkNote(noteData), api.db[noteKey])
    }
}
