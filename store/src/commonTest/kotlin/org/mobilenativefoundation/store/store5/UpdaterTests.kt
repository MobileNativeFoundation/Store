package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import org.mobilenativefoundation.store.store5.util.fake.Notes
import org.mobilenativefoundation.store.store5.util.fake.NotesApi
import org.mobilenativefoundation.store.store5.util.fake.NotesBookkeeping
import org.mobilenativefoundation.store.store5.util.fake.NotesConverterProvider
import org.mobilenativefoundation.store.store5.util.fake.NotesDatabase
import org.mobilenativefoundation.store.store5.util.fake.NotesKey
import org.mobilenativefoundation.store.store5.util.fake.NotesUpdaterProvider
import org.mobilenativefoundation.store.store5.util.fake.NotesValidator
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse
import org.mobilenativefoundation.store.store5.util.model.SOTNote
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
    fun givenNonEmptyMarketWhenWriteThenStoredAndAPIUpdated() = testScope.runTest {
        val ttl = inHours(1)

        val converter = NotesConverterProvider().provide()
        val validator = NotesValidator()
        val updater = NotesUpdaterProvider(api).provide()
        val bookkeeper = Bookkeeper.by(
            getLastFailedSync = bookkeeping::getLastFailedSync,
            setLastFailedSync = bookkeeping::setLastFailedSync,
            clear = bookkeeping::clear,
            clearAll = bookkeeping::clear
        )

        val store = MutableStoreBuilder.from<NotesKey, NetworkNote, CommonNote, SOTNote>(
            fetcher = Fetcher.of { key -> api.get(key, ttl = ttl) },
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

        val readRequest = StoreReadRequest.fresh(NotesKey.Single(Notes.One.id))

        val stream = store.stream<NotesWriteResponse>(readRequest)

        // Read is success
        assertEmitsExactly(
            stream,
            listOf(
                StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher),
                StoreReadResponse.Data(
                    CommonNote(NoteData.Single(Notes.One), ttl = ttl),
                    StoreReadResponseOrigin.Fetcher
                )
            )
        )

        val newNote = Notes.One.copy(title = "New Title-1")
        val writeRequest = StoreWriteRequest.of<NotesKey, CommonNote, NotesWriteResponse>(
            key = NotesKey.Single(Notes.One.id),
            value = CommonNote(NoteData.Single(newNote))
        )

        val storeWriteResponse = store.write(writeRequest)

        // Write is success
        assertEquals(
            StoreWriteResponse.Success.Typed(NotesWriteResponse(NotesKey.Single(Notes.One.id), true)),
            storeWriteResponse
        )

        val cachedReadRequest = StoreReadRequest.cached(NotesKey.Single(Notes.One.id), refresh = false)
        val cachedStream = store.stream<NotesWriteResponse>(cachedReadRequest)

        // Cache + SOT are updated
        val firstResponse = cachedStream.first()
        assertEquals(
            StoreReadResponse.Data(
                CommonNote(NoteData.Single(newNote), ttl = null),
                StoreReadResponseOrigin.Cache
            ), firstResponse
        )

        val secondResponse = cachedStream.take(2).last()
        assertIs<StoreReadResponse.Data<CommonNote>>(secondResponse)
        val data = secondResponse.value.data
        assertIs<NoteData.Single>(data)
        assertNotNull(data)
        assertEquals(newNote, data.item)
        assertEquals(StoreReadResponseOrigin.SourceOfTruth, secondResponse.origin)
        assertNotNull(secondResponse.value.ttl)

        // API is updated
        assertEquals(
            StoreWriteResponse.Success.Typed(NotesWriteResponse(NotesKey.Single(Notes.One.id), true)),
            storeWriteResponse
        )
        assertEquals(NetworkNote(NoteData.Single(newNote), ttl = null), api.db[NotesKey.Single(Notes.One.id)])
    }

    @Test
    fun givenNonEmptyMarketWithValidatorWhenInvalidThenSuccessOriginatingFromFetcher() = testScope.runTest {
        val ttl = inHours(1)

        val converter = NotesConverterProvider().provide()
        val validator = NotesValidator(expiration = inHours(12))
        val updater = NotesUpdaterProvider(api).provide()
        val bookkeeper = Bookkeeper.by(
            getLastFailedSync = bookkeeping::getLastFailedSync,
            setLastFailedSync = bookkeeping::setLastFailedSync,
            clear = bookkeeping::clear,
            clearAll = bookkeeping::clear
        )

        val store = MutableStoreBuilder.from<NotesKey, NetworkNote, CommonNote, SOTNote>(
            fetcher = Fetcher.of { key -> api.get(key, ttl = ttl) },
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

        val readRequest = StoreReadRequest.fresh(NotesKey.Single(Notes.One.id))

        val stream = store.stream<NotesWriteResponse>(readRequest)

        // Fetch is success and validator is not used
        assertEmitsExactly(
            stream,
            listOf(
                StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher),
                StoreReadResponse.Data(
                    CommonNote(NoteData.Single(Notes.One), ttl = ttl),
                    StoreReadResponseOrigin.Fetcher
                )
            )
        )

        val cachedReadRequest = StoreReadRequest.cached(NotesKey.Single(Notes.One.id), refresh = false)
        val cachedStream = store.stream<NotesWriteResponse>(cachedReadRequest)

        // Cache + SOT are updated
        // But item is invalid
        // So we do not emit value in cache or SOT
        // Instead we get latest from network even though refresh = false

        assertEmitsExactly(
            cachedStream,
            listOf(
                StoreReadResponse.Data(
                    CommonNote(NoteData.Single(Notes.One), ttl = ttl),
                    StoreReadResponseOrigin.Fetcher
                )
            )
        )
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

        val store = MutableStoreBuilder.from<NotesKey, NetworkNote, CommonNote, SOTNote>(
            fetcher = Fetcher.ofFlow { key ->
                val network = api.get(key)
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

        val newNote = Notes.One.copy(title = "New Title-1")
        val writeRequest = StoreWriteRequest.of<NotesKey, CommonNote, NotesWriteResponse>(
            key = NotesKey.Single(Notes.One.id),
            value = CommonNote(NoteData.Single(newNote))
        )
        val storeWriteResponse = store.write(writeRequest)

        assertEquals(
            StoreWriteResponse.Success.Typed(NotesWriteResponse(NotesKey.Single(Notes.One.id), true)),
            storeWriteResponse
        )
        assertEquals(NetworkNote(NoteData.Single(newNote)), api.db[NotesKey.Single(Notes.One.id)])
    }
}
