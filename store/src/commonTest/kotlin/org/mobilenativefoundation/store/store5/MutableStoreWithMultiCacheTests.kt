package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.cache5.MultiCache
import org.mobilenativefoundation.store.store5.util.fake.NoteCollections
import org.mobilenativefoundation.store.store5.util.fake.Notes
import org.mobilenativefoundation.store.store5.util.fake.NotesApi
import org.mobilenativefoundation.store.store5.util.fake.NotesDatabase
import org.mobilenativefoundation.store.store5.util.fake.NotesKey
import org.mobilenativefoundation.store.store5.util.fake.NotesMemoryCache
import org.mobilenativefoundation.store.store5.util.model.InputNote
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.NoteData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MutableStoreWithMultiCacheTests {
    private val testScope = TestScope()
    private lateinit var api: NotesApi
    private lateinit var database: NotesDatabase

    @BeforeTest
    fun before() {
        api = NotesApi()
        database = NotesDatabase()
    }

    @Test
    fun givenEmptyStoreWhenListFromFetcherThenListIsDecomposed() = testScope.runTest {
        val memoryCache =
            NotesMemoryCache(MultiCache(CacheBuilder()))

        val converter: Converter<NetworkNote, NetworkNote, NoteData> = Converter.Builder<NetworkNote, NetworkNote, NoteData>()
            .fromNetworkToLocal { network: NetworkNote -> network }
            .fromOutputToLocal { output: NoteData -> NetworkNote(output, Long.MAX_VALUE) }
            .build()
        val store = StoreBuilder.from(
            fetcher = Fetcher.of { key -> api.get(key) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> database.get(key)!!.data },
                writer = { key, note -> database.put(key, InputNote(note.data, Long.MAX_VALUE)) },
                delete = null,
                deleteAll = null
            ),
            memoryCache = memoryCache
        ).toMutableStoreBuilder(
            converter
        ).build(
                updater = Updater.by(
                    post = { _, _ -> UpdaterResult.Error.Exception(Exception()) }
                )
            )

        val freshRequest =
            StoreReadRequest.fresh(NotesKey.Collection(NoteCollections.Keys.OneAndTwo))

        val freshStream = store.stream<UpdaterResult>(freshRequest)

        val actualResultFromFreshStream = freshStream.take(2).toList()
        val expectedResultFromFreshStream = listOf(
            StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
            StoreReadResponse.Data(NoteCollections.OneAndTwo, StoreReadResponseOrigin.Fetcher())
        )

        assertEquals(expectedResultFromFreshStream, actualResultFromFreshStream)

        val singleFromMemoryCache = memoryCache.getIfPresent(NotesKey.Single(Notes.One.id))
        assertIs<NoteData.Single>(singleFromMemoryCache)
        assertEquals(singleFromMemoryCache.item, Notes.One)

        val cachedRequest = StoreReadRequest.cached(NotesKey.Single(Notes.One.id), refresh = true)
        val cachedStream = store.stream<UpdaterResult>(cachedRequest)
        val actualResultFromCachedStream = cachedStream.take(1).toList()
        val expectedResultFromCachedStream = listOf(
            StoreReadResponse.Data(NoteData.Single(Notes.One), StoreReadResponseOrigin.Cache)
        )

        assertEquals(expectedResultFromCachedStream, actualResultFromCachedStream)
    }
}
