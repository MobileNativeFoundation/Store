@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.data.model.MarketData
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

internal class FakeComplexDatabase : Store<NoteMarketKey, NoteMarketInput, NoteMarketOutput> {
    private val data: MutableMap<NoteMarketKey, NoteMarketOutput> = mutableMapOf()
    private val writeRequests: MutableMap<String, Long?> = mutableMapOf()

    private fun NoteMarketInput.convert(): NoteMarketOutput = when (this.data) {
        is MarketData.Collection -> NoteMarketOutput.Read(this.data)
        is MarketData.Single -> NoteMarketOutput.Read(this.data)
        null -> NoteMarketOutput.Read(null)
    }

    private fun NoteMarketKey.Write.convert(): NoteMarketKey.Read = NoteMarketKey.Read.GetById(note.id)

    override fun read(key: NoteMarketKey): Flow<NoteMarketOutput?> = flow {
        if (key is NoteMarketKey.Read) {
            emit(data[key])
        } else {
            emit(null)
        }
    }

    override suspend fun write(key: NoteMarketKey, input: NoteMarketInput): Boolean {
        return when (key) {
            is NoteMarketKey.Read.GetById -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key] = NoteMarketOutput.Read(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key] = NoteMarketOutput.Read(input.data)
                    true
                }

                null -> {
                    false
                }
            }

            is NoteMarketKey.Read.Paginate -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key] = NoteMarketOutput.Read(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key] = NoteMarketOutput.Read(input.data)
                    true
                }

                null -> {
                    false
                }
            }

            is NoteMarketKey.Write -> when (input.data) {
                is MarketData.Collection -> {
                    input.data.items.forEach {
                        data[key.convert()] = NoteMarketOutput.Read(MarketData.Single(it))
                    }
                    true
                }

                is MarketData.Single -> {
                    data[key.convert()] = NoteMarketOutput.Read(input.data)
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
