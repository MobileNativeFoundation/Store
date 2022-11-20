package org.mobilenativefoundation.store.notes.app.wiring

import android.app.Application
import android.content.Context
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
import org.mobilenativefoundation.store.notes.app.market.Key

@Module
@ContributesTo(AppScope::class)
object AppModule {
    private val memoryLruCache = MemoryLruCache(10)
    private val serializer = Json { ignoreUnknownKeys = true }

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

    @Provides
    @SingleIn(AppScope::class)
    fun provideMemoryLruCacheStore(): Store<Key, Note, Note> = Store.by(
        read = { key -> memoryLruCache.read(serializer.encodeToString(key)) },
        write = { key, input -> memoryLruCache.write(serializer.encodeToString(key), input) },
        delete = { key -> memoryLruCache.delete(serializer.encodeToString(key)) },
        deleteAll = { memoryLruCache.deleteAll() }
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseStore(): Store<Key, Note, Note> = Store.by(
        read = { key -> memoryLruCache.read(serializer.encodeToString(key)) },
        write = { key, input -> memoryLruCache.write(serializer.encodeToString(key), input) },
        delete = { key -> memoryLruCache.delete(serializer.encodeToString(key)) },
        deleteAll = { memoryLruCache.deleteAll() }
    )
}