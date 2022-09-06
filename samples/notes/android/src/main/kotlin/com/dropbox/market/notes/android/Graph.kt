package com.dropbox.market.notes.android

import android.content.Context
import com.dropbox.market.notes.android.data.Notebook
import com.dropbox.market.notes.android.data.api.Api
import com.dropbox.market.notes.android.data.api.NotesApi
import com.dropbox.market.notes.android.data.asMarketNote
import com.dropbox.market.notes.android.data.db.tryClear
import com.dropbox.market.notes.android.data.db.tryDelete
import com.dropbox.market.notes.android.data.db.tryWrite
import com.dropbox.store.ConflictResolution
import com.dropbox.store.Market
import com.dropbox.store.Store
import com.dropbox.store.impl.ShareableLruCache
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Contextual
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object Graph {

    @Contextual
    private val serializer = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideDriver(@ApplicationContext context: Context): SqlDriver =
        AndroidSqliteDriver(NotesDatabase.Schema, context, "notes.database")

    @Provides
    @Singleton
    fun provideDatabase(driver: SqlDriver): NotesDatabase =
        NotesDatabase(driver)

    @Provides
    fun provideCoroutineDispatcher() = Dispatchers.Default

    @Provides
    fun provideHttpClient() = HttpClient {
        install(ContentNegotiation) {
            json()
        }
    }

    @Provides
    @Singleton
    fun provideApi(client: HttpClient): Api<Key, Notebook> = NotesApi(client)

    @Provides
    @Singleton
    fun provideConflictResolution(database: NotesDatabase): ConflictResolution<Key, Notebook, Notebook> {
        return ConflictResolution.Builder<Key, Notebook, Notebook>()
            .getLastFailedWriteTime { key ->
                when (key) {
                    Key.All -> null
                    is Key.Single -> database.writeRequestFailureQueries.read(key.key).executeAsOneOrNull()?.datetime
                }
            }
            .setLastFailedWriteTime { key, datetime ->
                when (key) {
                    Key.All -> false
                    is Key.Single -> {
                        try {
                            database.writeRequestFailureQueries.write(WriteRequestFailure(key.key, datetime))
                            true
                        } catch (throwable: Throwable) {
                            false
                        }
                    }
                }
            }
            .deleteFailedWriteRecord { key ->
                when (key) {
                    Key.All -> false
                    is Key.Single -> {
                        try {
                            database.writeRequestFailureQueries.delete(key.key)
                            true
                        } catch (throwable: Throwable) {
                            false
                        }
                    }
                }
            }
            .build()
    }

    @Provides
    @Named("MemoryLruCacheStore")
    @Singleton
    fun provideMemoryLruCacheStore(): Store<Key, Notebook, Notebook> {
        val memoryLruCache = ShareableLruCache(10)
        return Store.Builder<Key, Notebook, Notebook>()
            .read { key -> memoryLruCache.read(serializer.encodeToString(key)) }
            .write { key, input ->
                when (input) {
                    is Notebook.Notes -> {
                        input.values.forEach { memoryLruCache.write(serializer.encodeToString(it.key), it) }
                        true
                    }

                    is Notebook.Note -> memoryLruCache.write(serializer.encodeToString(key), input)
                }
            }
            .delete { key -> memoryLruCache.delete(serializer.encodeToString(key)) }
            .clear { memoryLruCache.clear() }
            .build()
    }

    @Provides
    @Named("DatabaseStore")
    @Singleton
    fun provideDatabaseStore(database: NotesDatabase): Store<Key, Notebook, Notebook> {
        return Store.Builder<Key, Notebook, Notebook>()
            .read { key ->
                flow {
                    when (key) {
                        Key.All -> database.noteQueries
                            .getAll()
                            .executeAsList()
                            .let { notes -> emit(Notebook.Notes(notes.map { note -> note.asMarketNote() })) }

                        is Key.Single -> database.noteQueries
                            .read(key.key)
                            .executeAsList()
                            .let { notes -> emit(Notebook.Note(notes.last().asMarketNote())) }
                    }
                }
            }
            .write { key, input -> database.tryWrite(key, input) }
            .delete { key -> database.tryDelete(serializer.encodeToString(key)) }
            .clear { database.tryClear() }
            .build()
    }

    @Provides
    @Named("MarketScope")
    @Singleton
    fun provideMarketScope(coroutineDispatcher: CoroutineDispatcher) = CoroutineScope(coroutineDispatcher)

    @Provides
    @Singleton
    fun provideMarket(
        @Named("MarketScope") coroutineScope: CoroutineScope,
        @Named("MemoryLruCacheStore") memoryLruCacheStore: Store<Key, Notebook, Notebook>,
        @Named("DatabaseStore") databaseStore: Store<Key, Notebook, Notebook>,
        conflictResolution: ConflictResolution<Key, Notebook, Notebook>
    ): Market<Key> = Market.of(
        coroutineScope = coroutineScope,
        stores = listOf(memoryLruCacheStore, databaseStore),
        conflictResolution = conflictResolution
    )
}