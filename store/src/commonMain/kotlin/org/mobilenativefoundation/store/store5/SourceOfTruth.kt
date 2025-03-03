/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobilenativefoundation.store.store5

import kotlin.jvm.JvmName
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.impl.PersistentNonFlowingSourceOfTruth
import org.mobilenativefoundation.store.store5.impl.PersistentSourceOfTruth

/**
 * [SourceOfTruth], as name implies, is the persistence API which [Store] uses to serve values to
 * the collectors. If provided, [Store] will only return values received from [SourceOfTruth] back
 * to the collectors.
 *
 * In other words, values coming from the [Fetcher] will always be sent to the [SourceOfTruth] and
 * will be read back via [reader] to be returned to the collector.
 *
 * This round-trip ensures the data is consistent across the application in case the [Fetcher] may
 * not return all fields or return a different class type than the app uses. It is particularly
 * useful if your application has a local observable database which is directly modified by the app
 * as Store can observe these changes and update the collectors even before value is synced to the
 * backend.
 *
 * Source of truth takes care of making any source (no matter if it has flowing reads or not) into a
 * common flowing API.
 *
 * A source of truth is usually backed by local storage. It's purpose is to eliminate the need for
 * waiting on network update before local modifications are available (via [Store.Stream.read]).
 *
 * For maximal flexibility, [writer]'s record type ([Input]] and [reader]'s record type ([Output])
 * are not identical. This allows us to read one type of objects from network and transform them to
 * another type when placing them in local storage.
 */
interface SourceOfTruth<Key : Any, Local : Any, Output : Any> {
  /**
   * Used by [Store] to read records from the source of truth.
   *
   * @param key The key to read for.
   */
  fun reader(key: Key): Flow<Output?>

  /**
   * Used by [Store] to write records **coming in from the fetcher (network)** to the source of
   * truth.
   *
   * **Note:** [Store] currently does not support updating the source of truth with local user
   * updates (i.e writing record of type [Output]). However, any changes in the local database will
   * still be visible via [Store.Stream.read] APIs as long as you are using a local storage that
   * supports observability (e.g. Room, SQLDelight, Realm).
   *
   * @param key The key to update for.
   */
  suspend fun write(key: Key, value: Local)

  /**
   * Used by [Store] to delete records in the source of truth for the given key.
   *
   * @param key The key to delete for.
   */
  suspend fun delete(key: Key)

  /** Used by [Store] to delete all records in the source of truth. */
  suspend fun deleteAll()

  companion object {
    /**
     * Creates a (non-[Flow]) source of truth that is accessible via [nonFlowReader], [writer],
     * [delete] and [deleteAll].
     *
     * @param nonFlowReader function for reading records from the source of truth
     * @param writer function for writing updates to the backing source of truth
     * @param delete function for deleting records in the source of truth for the given key
     * @param deleteAll function for deleting all records in the source of truth
     */
    fun <Key : Any, Local : Any, Output : Any> of(
      nonFlowReader: suspend (Key) -> Output?,
      writer: suspend (Key, Local) -> Unit,
      delete: (suspend (Key) -> Unit)? = null,
      deleteAll: (suspend () -> Unit)? = null,
    ): SourceOfTruth<Key, Local, Output> =
      PersistentNonFlowingSourceOfTruth(
        realReader = nonFlowReader,
        realWriter = writer,
        realDelete = delete,
        realDeleteAll = deleteAll,
      )

    /**
     * Creates a ([Flow]) source of truth that is accessed via [reader], [writer], [delete] and
     * [deleteAll].
     *
     * @param reader function for reading records from the source of truth
     * @param writer function for writing updates to the backing source of truth
     * @param delete function for deleting records in the source of truth for the given key
     * @param deleteAll function for deleting all records in the source of truth
     */
    @JvmName("ofFlow")
    fun <Key : Any, Local : Any, Output : Any> of(
      reader: (Key) -> Flow<Output?>,
      writer: suspend (Key, Local) -> Unit,
      delete: (suspend (Key) -> Unit)? = null,
      deleteAll: (suspend () -> Unit)? = null,
    ): SourceOfTruth<Key, Local, Output> =
      PersistentSourceOfTruth(
        realReader = reader,
        realWriter = writer,
        realDelete = delete,
        realDeleteAll = deleteAll,
      )
  }

  /**
   * The exception provided when a write operation fails in SourceOfTruth.
   *
   * see [StoreReadResponse.Error.Exception]
   */
  class WriteException(
    /** The key for the failed write attempt */
    val key: Any?, // TODO why are we not marking keys non-null ?
    /** The value for the failed write attempt */
    val value: Any?,
    /** The exception thrown from the [SourceOfTruth]'s [write] method. */
    cause: Throwable,
  ) : RuntimeException("Failed to write value to Source of Truth. key: $key", cause) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other == null || this::class != other::class) return false

      other as WriteException

      if (key != other.key) return false
      if (value != other.value) return false
      if (cause != other.cause) return false
      return true
    }

    override fun hashCode(): Int {
      var result = key.hashCode()
      result = 31 * result + value.hashCode()
      return result
    }
  }

  /**
   * Exception created when a [reader] throws an exception.
   *
   * see [StoreReadResponse.Error.Exception]
   */
  class ReadException(
    /** The key for the failed write attempt */
    val key: Any?, // TODO shouldn't key be non-null?
    cause: Throwable,
  ) : RuntimeException("Failed to read from Source of Truth. key: $key", cause) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other == null || this::class != other::class) return false

      other as ReadException

      if (key != other.key) return false
      if (cause != other.cause) return false
      return true
    }

    override fun hashCode(): Int {
      return key.hashCode()
    }
  }
}
