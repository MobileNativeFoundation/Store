package org.mobilenativefoundation.store.store5.mutablestore.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.SourceOfTruth

@Suppress("UNCHECKED_CAST")
class TestSourceOfTruth<Key : Any, Local : Any, Output : Any> : SourceOfTruth<Key, Local, Output> {
  private val storage = HashMap<Key, Local?>()
  private val flows = HashMap<Key, MutableSharedFlow<Output?>>()
  private var readError: Throwable? = null
  private var writeError: Throwable? = null
  private var deleteError: Throwable? = null
  private var deleteAllError: Throwable? = null

  fun throwOnRead(key: Key, block: () -> Throwable) {
    readError = block()
  }

  fun throwOnWrite(key: Key, block: () -> Throwable) {
    writeError = block()
  }

  fun throwOnDelete(key: Key?, block: () -> Throwable) {
    if (key != null) deleteError = block() else deleteAllError = block()
  }

  override fun reader(key: Key): Flow<Output?> = flow {
    readError?.let { throw SourceOfTruth.ReadException(key, it) }
    val sharedFlow = flows.getOrPut(key) { MutableSharedFlow(replay = 1) }
    emit(storage[key] as Output?)
    emitAll(sharedFlow)
  }

  override suspend fun write(key: Key, value: Local) {
    writeError?.let { throw SourceOfTruth.WriteException(key, value, it) }
    storage[key] = value
    flows[key]?.emit(value as Output?)
  }

  override suspend fun delete(key: Key) {
    deleteError?.let { throw it }
    storage.remove(key)
    flows.remove(key)
  }

  override suspend fun deleteAll() {
    deleteAllError?.let { throw it }
    storage.clear()
    flows.clear()
  }
}
