@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.market.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.market.Store
import org.mobilenativefoundation.store.store5.market.data.model.MarketData
import org.mobilenativefoundation.store.store5.market.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketOutput
import org.mobilenativefoundation.store.store5.market.data.model.NoteStoreDatabaseRepresentation

internal class FakeComplexDatabase : Store<NoteMarketKey, NoteStoreDatabaseRepresentation, NoteCommonRepresentation> {
    private val data: MutableMap<NoteMarketKey, NoteStoreDatabaseRepresentation> = mutableMapOf()
    private val writeRequests: MutableMap<String, Long?> = mutableMapOf()

    private fun NoteMarketInput.convert(): NoteMarketOutput = when (this.data) {
        is MarketData.Collection -> NoteMarketOutput.Read(this.data)
        is MarketData.Single -> NoteMarketOutput.Read(this.data)
        null -> NoteMarketOutput.Read(null)
    }

    private fun NoteMarketKey.Write.convert(): NoteMarketKey.Read = NoteMarketKey.Read.GetById(note.id)

    override fun read(key: NoteMarketKey): Flow<NoteCommonRepresentation?> = flow {
        val storeRepresentation = data[key]
        if (key is NoteMarketKey.Read && storeRepresentation != null) {
            emit(converter.toCommonRepresentation(storeRepresentation))
        } else {
            emit(null)
        }
    }

    override suspend fun write(key: NoteMarketKey, input: NoteCommonRepresentation): Boolean {
        return when (key) {
            is NoteMarketKey.Read.GetById -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key] = NoteStoreDatabaseRepresentation(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key] = converter.toStoreRepresentation(input)
                    true
                }

                null -> {
                    false
                }
            }

            is NoteMarketKey.Read.Paginate -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key] = NoteStoreDatabaseRepresentation(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key] = converter.toStoreRepresentation(input)
                    true
                }

                null -> {
                    false
                }
            }

            is NoteMarketKey.Write -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key.convert()] = NoteStoreDatabaseRepresentation(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key.convert()] = converter.toStoreRepresentation(input)
                    true
                }

                null -> {
                    false
                }
            }
        }
    }

    override suspend fun delete(key: NoteMarketKey): Boolean {
        return if (key in data) {
            data.remove(key)
            true
        } else {
            false
        }
    }

    override suspend fun clear(): Boolean {
        data.clear()
        return true
    }

    override val converter = object : Store.Converter<NoteStoreDatabaseRepresentation, NoteCommonRepresentation> {
        override fun toCommonRepresentation(storeRepresentation: NoteStoreDatabaseRepresentation): NoteCommonRepresentation =
            NoteCommonRepresentation(storeRepresentation.data)

        override fun toStoreRepresentation(commonRepresentation: NoteCommonRepresentation): NoteStoreDatabaseRepresentation =
            NoteStoreDatabaseRepresentation(commonRepresentation.data)
    }

    fun setLastWriteTime(key: String, updated: Long?): Boolean {
        this.writeRequests[key] = updated
        return true
    }

    fun getLastWriteTime(key: String): Long? = this.writeRequests[key]

    fun deleteWriteRequest(key: String): Boolean {
        this.writeRequests.remove(key)
        return true
    }

    fun deleteAllWriteRequests(): Boolean {
        this.writeRequests.clear()
        return true
    }

    fun reset() {
        data.clear()
        this.writeRequests.clear()
    }
}
