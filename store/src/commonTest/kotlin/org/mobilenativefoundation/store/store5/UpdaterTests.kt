package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore
import org.mobilenativefoundation.store.store5.util.fake.NotesApi
import org.mobilenativefoundation.store.store5.util.fake.NotesBookkeeping
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

    @BeforeTest
    fun before() {
        api = NotesApi()
        bookkeeping = NotesBookkeeping()
    }

    @Test
    fun givenEmptyMarketWhenWriteThenSuccessResponsesAndApiUpdated() = testScope.runTest {
        val updater = Updater.by<String, CommonNote, NotesWriteResponse>(
            post = { key, common ->
                val response = api.post(key, common)
                if (response.ok) {
                    UpdaterResult.Success.Typed(response)
                } else {
                    UpdaterResult.Error.Message("Failed to sync")
                }
            }
        )
        val bookkeeper = Bookkeeper.by(
            getLastFailedSync = bookkeeping::getLastFailedSync,
            setLastFailedSync = bookkeeping::setLastFailedSync,
            clear = bookkeeping::clear,
            clearAll = bookkeeping::clear
        )

        val store = StoreBuilder.from<String, NetworkNote, CommonNote>(
            fetcher = Fetcher.ofFlow { key ->
                val network = NetworkNote(NoteData.Single(Note("$key-id", "$key-title", "$key-content")))
                flow { emit(network) }
            }
        )
            .build()
            .asMutableStore<String, NetworkNote, CommonNote, SOTNote, NotesWriteResponse>(
                updater = updater,
                bookkeeper = bookkeeper
            )

        val noteKey = "1-id"
        val noteTitle = "1-title"
        val noteContent = "1-content"
        val noteData = NoteData.Single(Note(noteKey, noteTitle, noteContent))
        val writeRequest = StoreWriteRequest.of<String, CommonNote, NotesWriteResponse>(
            key = noteKey,
            value = CommonNote(noteData)
        )

        val storeWriteResponse = store.write(writeRequest)

        assertEquals(StoreWriteResponse.Success.Typed(NotesWriteResponse(noteKey, true)), storeWriteResponse)
        assertEquals(NetworkNote(noteData), api.db[noteKey])
    }
}
