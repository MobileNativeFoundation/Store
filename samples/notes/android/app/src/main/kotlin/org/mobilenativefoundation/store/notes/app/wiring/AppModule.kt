package org.mobilenativefoundation.store.notes.app.wiring

import android.app.Application
import android.content.Context
import com.dropbox.external.store5.Bookkeeper
import com.dropbox.external.store5.Market
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.impl.MemoryLruCache
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import dagger.Module
import dagger.Provides
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store.notes.android.app.NotesDatabase
import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import org.mobilenativefoundation.store.notes.android.common.scoping.SingleIn
import org.mobilenativefoundation.store.notes.app.extension.tryDeleteAllFailed
import org.mobilenativefoundation.store.notes.app.extension.tryDeleteAllNotes
import org.mobilenativefoundation.store.notes.app.extension.tryDeleteFailed
import org.mobilenativefoundation.store.notes.app.extension.tryDeleteNote
import org.mobilenativefoundation.store.notes.app.extension.tryGetFailed
import org.mobilenativefoundation.store.notes.app.extension.tryGetNote
import org.mobilenativefoundation.store.notes.app.extension.tryWriteFailed
import org.mobilenativefoundation.store.notes.app.extension.tryWriteNote
import org.mobilenativefoundation.store.notes.app.market.Key
import javax.inject.Named

@Module
@ContributesTo(AppScope::class)
object AppModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideContext(application: Application): Context = application.applicationContext

    @Provides
    @SingleIn(AppScope::class)
    fun provideDriver(context: Context): SqlDriver =
        AndroidSqliteDriver(NotesDatabase.Schema, context, "notes.database")

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driver: SqlDriver): NotesDatabase = NotesDatabase(driver)

    @Named(MEMORY_LRU_CACHE_STORE)
    @Provides
    @SingleIn(AppScope::class)
    fun provideMemoryLruCacheStore(): Store<Key, Note, Note> = Store.by(
        read = { key -> memoryLruCache.read(key.encode()) },
        write = { key, input -> memoryLruCache.write(key.encode(), input) },
        delete = { key -> memoryLruCache.delete(key.encode()) },
        deleteAll = { memoryLruCache.deleteAll() }
    )

    @Named(DATABASE_STORE)
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseStore(database: NotesDatabase): Store<Key, Note, Note> = Store.by(
        read = { key -> database.tryGetNote(key.encode()) },
        write = { key, input -> database.tryWriteNote(key.encode(), input) },
        delete = { key -> database.tryDeleteNote(key.encode()) },
        deleteAll = { database.tryDeleteAllNotes() }
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideBookkeeper(database: NotesDatabase): Bookkeeper<Key> = Bookkeeper.by(
        read = { key -> database.tryGetFailed(key.encode()) },
        write = { key, timestamp -> database.tryWriteFailed(key.encode(), timestamp) },
        delete = { key -> database.tryDeleteFailed(key.encode()) },
        deleteAll = { database.tryDeleteAllFailed() }
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideMarket(
        @Named(MEMORY_LRU_CACHE_STORE) memoryLruCacheStore: Store<Key, Note, Note>,
        @Named(DATABASE_STORE) databaseStore: Store<Key, Note, Note>,
        bookkeeper: Bookkeeper<Key>
    ): Market<Key> = Market.of(
        stores = listOf(memoryLruCacheStore, databaseStore),
        bookkeeper = bookkeeper
    )

    private val memoryLruCache = MemoryLruCache(10)
    private val serializer = Json { ignoreUnknownKeys = true }
    private fun Key.encode(): String = serializer.encodeToString(this)

    private const val MEMORY_LRU_CACHE_STORE = "MemoryLruCacheStore"
    private const val DATABASE_STORE = "DatabaseStore"
}