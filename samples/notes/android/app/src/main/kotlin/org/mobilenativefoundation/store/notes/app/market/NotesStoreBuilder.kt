package org.mobilenativefoundation.store.notes.app.market

import com.dropbox.external.store5.Store
import com.dropbox.external.store5.impl.MemoryLruCache
import com.squareup.anvil.annotations.ContributesBinding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store.notes.android.app.NotesDatabase
import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import org.mobilenativefoundation.store.notes.android.common.scoping.SingleIn
import javax.inject.Inject

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NotesStoreBuilder @Inject constructor(
    private val database: NotesDatabase
) {


    fun buildDiskStore(): Store<Key, Note, Note> = Store.by(
        read = { key -> database.read(serializer.encodeToString(key)) },
        write = { key, input -> database.write(serializer.encodeToString(key), input) },
        delete = { key -> database.delete(serializer.encodeToString(key)) },
        deleteAll = { database.deleteAll() }
    )


}