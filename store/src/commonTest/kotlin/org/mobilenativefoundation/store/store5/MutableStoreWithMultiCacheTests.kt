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
import org.mobilenativefoundation.store.store5.util.fake.NotesMemoryCache
import org.mobilenativefoundation.store.store5.util.fake.NotesKey
import org.mobilenativefoundation.store.store5.util.model.NetworkNote
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.SOTNote
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

//    @Test
//    fun givenEmptyStoreWhenListFromFetcherThenListIsDecomposed() = testScope.runTest {
//        val memoryCache = NotesMemoryCache(MultiCache<String, Note>(CacheBuilder()))
//
//        val store = StoreBuilder.from<NotesKey, NetworkNote, NoteData, SOTNote>(
//            fetcher = Fetcher.of<NotesKey, NetworkNote> { key -> api.get(key) },
//            sourceOfTruth = SourceOfTruth.of<NotesKey, SOTNote, NoteData>(
//                nonFlowReader = { key -> database.get(key) },
//                writer = { key, note -> database.put(key, note) },
//                delete = null,
//                deleteAll = null
//            ),
//            memoryCache = memoryCache
//        ).toMutableStoreBuilder<NetworkNote, SOTNote>()
//            .converter(
//                Converter.Builder<NetworkNote, NoteData, SOTNote>()
//                    .fromLocalToOutput { local -> local.data!! }
//                    .fromNetworkToOutput { network -> network.data!! }
//                    .fromOutputToLocal { output -> SOTNote(output, Long.MAX_VALUE) }
//                    .build()
//            ).build(
//                updater = Updater.by(
//                    post = { _, _ -> UpdaterResult.Error.Exception(Exception()) }
//                )
//            )
//
//        val freshRequest = StoreReadRequest.fresh(NotesKey.Collection(NoteCollections.Keys.OneAndTwo))
//
//        val freshStream = store.stream<UpdaterResult>(freshRequest)
//
//        val actualResultFromFreshStream = freshStream.take(2).toList()
//        val expectedResultFromFreshStream = listOf(
//            StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
//            StoreReadResponse.Data(NoteCollections.OneAndTwo, StoreReadResponseOrigin.Fetcher())
//        )
//
//        assertEquals(expectedResultFromFreshStream, actualResultFromFreshStream)
//
//        val singleFromMemoryCache = memoryCache.getIfPresent(NotesKey.Single(Notes.One.id))
//        assertIs<NoteData.Single>(singleFromMemoryCache)
//        assertEquals(singleFromMemoryCache.item, Notes.One)
//
//        val cachedRequest = StoreReadRequest.cached(NotesKey.Single(Notes.One.id), refresh = true)
//        val cachedStream = store.stream<UpdaterResult>(cachedRequest)
//        val actualResultFromCachedStream = cachedStream.take(1).toList()
//        val expectedResultFromCachedStream = listOf(
//            StoreReadResponse.Data(NoteData.Single(Notes.One), StoreReadResponseOrigin.Cache)
//        )
//
//        assertEquals(expectedResultFromCachedStream, actualResultFromCachedStream)
//    }
}
