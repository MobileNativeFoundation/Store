package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.cache5.HybridCache
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteData

internal class NotesHybridCache(private val delegate: HybridCache<String, Note>) : Cache<NotesKey, NoteData> {
    override fun getIfPresent(key: NotesKey): NoteData? = when (key) {
        is NotesKey.Collection -> {
            val items = delegate.getList(key.id)
            if (items != null) {
                NoteData.Collection(items)
            } else {
                null
            }
        }

        is NotesKey.Single -> {
            val item = delegate.getItem(key.id)
            if (item != null) {
                NoteData.Single(item)
            } else {
                null
            }
        }
    }

    override fun getOrPut(key: NotesKey, valueProducer: () -> NoteData): NoteData {
        val collection = getIfPresent(key)
        return if (collection == null) {
            val noteData = valueProducer()
            put(key, noteData)
            noteData
        } else {
            collection
        }
    }

    override fun getAllPresent(keys: List<*>): Map<NotesKey, NoteData> {
        val map = mutableMapOf<NotesKey, NoteData>()

        keys.filterIsInstance<NotesKey>().forEach { key ->
            val noteData = getIfPresent(key)
            if (noteData != null) {
                map[key] = noteData
            }
        }

        return map
    }

    override fun invalidateAll() {
        // TODO()
    }

    override fun size(): Long {
        // TODO()
        return Long.MAX_VALUE
    }

    override fun invalidateAll(keys: List<NotesKey>) {
        // TODO()
    }

    override fun invalidate(key: NotesKey) {
        // TODO()
    }

    override fun putAll(map: Map<NotesKey, NoteData>) {
        map.entries.forEach { (key, noteData) ->
            put(key, noteData)
        }
    }

    override fun put(key: NotesKey, value: NoteData) = when (key) {
        is NotesKey.Collection -> {
            require(value is NoteData.Collection)
            delegate.putList(key.id, value.items)
        }

        is NotesKey.Single -> {
            require(value is NoteData.Single)
            delegate.putItem(key.id, value.item)
        }
    }
}