package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.fake.NoteApi
import org.mobilenativefoundation.store.store5.util.fake.NoteBookkeeping
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NoteNetworkRepresentation
import org.mobilenativefoundation.store.store5.util.model.NoteNetworkWriteResponse
import org.mobilenativefoundation.store.store5.util.model.NoteSourceOfTruthRepresentation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class UpdaterTests {
    private val testScope = TestScope()
    private lateinit var api: NoteApi
    private lateinit var bookkeeping: NoteBookkeeping

    @BeforeTest
    fun before() {
        api = NoteApi()
        bookkeeping = NoteBookkeeping()
    }

    @Test
    fun givenEmptyMarketWhenWriteThenSuccessResponsesAndApiUpdated() = testScope.runTest {
        val store =
            MutableStoreBuilder.from<String, NoteNetworkRepresentation, NoteCommonRepresentation, NoteSourceOfTruthRepresentation, NoteNetworkWriteResponse>(
                fetcher = Fetcher.ofFlow { key ->
                    val networkRepresentation = NoteNetworkRepresentation(NoteData.Single(Note("$key-id", "$key-title", "$key-content")))
                    flow { emit(networkRepresentation) }
                },
                updater = Updater.by(
                    post = { key, commonRepresentation ->
                        val networkWriteResponse = api.post(key, commonRepresentation)
                        if (networkWriteResponse.ok) {
                            UpdaterResult.Success(networkWriteResponse)
                        } else {
                            UpdaterResult.Error.Message("Failed to sync")
                        }
                    },
                ),
                bookkeeper = Bookkeeper.by(
                    getLastFailedSync = bookkeeping::getLastFailedSync,
                    setLastFailedSync = bookkeeping::setLastFailedSync,
                    clear = bookkeeping::clear,
                    clearAll = bookkeeping::clear
                )
            ).build()

        val noteKey = "1-id"
        val noteTitle = "1-title"
        val noteContent = "1-content"
        val noteData = NoteData.Single(Note(noteKey, noteTitle, noteContent))
        val writeRequest = StoreWriteRequest.of<String, NoteCommonRepresentation, NoteNetworkWriteResponse>(
            key = noteKey,
            input = NoteCommonRepresentation(noteData)
        )

        val storeWriteResponse = store.write(writeRequest)

        assertEquals(StoreWriteResponse.Success(NoteNetworkWriteResponse(noteKey, true)), storeWriteResponse)
        assertEquals(NoteNetworkRepresentation(noteData), api.db[noteKey])
    }
}
