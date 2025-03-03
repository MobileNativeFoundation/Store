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
package org.mobilenativefoundation.store.store5.impl

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.CacheType
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.operators.Either
import org.mobilenativefoundation.store.store5.impl.operators.merge
import org.mobilenativefoundation.store.store5.internal.result.StoreDelegateWriteResult

internal class RealStore<Key : Any, Network : Any, Output : Any, Local : Any>(
  scope: CoroutineScope,
  fetcher: Fetcher<Key, Network>,
  sourceOfTruth: SourceOfTruth<Key, Local, Output>? = null,
  private val converter: Converter<Network, Local, Output>,
  private val validator: Validator<Output>?,
  private val memCache: Cache<Key, Output>?,
) : Store<Key, Output> {
  /**
   * This source of truth is either a real database or an in memory source of truth created by the
   * builder. Whatever is given, we always put a [SourceOfTruthWithBarrier] in front of it so that
   * while we write the value from fetcher into the disk, we can block reads to avoid sending new
   * data as if it came from the server (the [StoreReadResponse.origin] field).
   */
  private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Network, Output, Local>? =
    sourceOfTruth?.let { SourceOfTruthWithBarrier(it, converter) }

  /**
   * Fetcher controller maintains 1 and only 1 `Multicaster` for a given key to ensure network
   * requests are shared.
   */
  private val fetcherController =
    FetcherController(
      scope = scope,
      realFetcher = fetcher,
      sourceOfTruth = this.sourceOfTruth,
      converter = converter,
    )

  @Suppress("UNCHECKED_CAST")
  override fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>> =
    flow {
        val cachedToEmit =
          if (request.shouldSkipCache(CacheType.MEMORY)) {
            null
          } else {
            val output: Output? = memCache?.getIfPresent(request.key)
            val isInvalid = output != null && validator?.isValid(output) == false
            when {
              output == null || isInvalid -> null
              else -> output
            }
          }

        cachedToEmit?.let { it: Output ->
          // if we read a value from cache, dispatch it first
          emit(StoreReadResponse.Data(value = it, origin = StoreReadResponseOrigin.Cache))
        }

        if (sourceOfTruth == null && !request.fetch) {
          if (memCache == null) {
            logger.w("Local-only request made with no cache or source of truth configured")
          }
          emit(StoreReadResponse.NoNewData(origin = StoreReadResponseOrigin.Cache))
          return@flow
        }

        val stream: Flow<StoreReadResponse<Output>> =
          if (sourceOfTruth == null) {
            // piggyback only if not specified fresh data AND we emitted a value from the cache
            val piggybackOnly = !request.refresh && cachedToEmit != null
            @Suppress("UNCHECKED_CAST")
            createNetworkFlow(request = request, networkLock = null, piggybackOnly = piggybackOnly)
              as Flow<StoreReadResponse<Output>> // when no source of truth Input == Output
          } else if (request.fetch) {
            diskNetworkCombined(request, sourceOfTruth)
          } else {
            val diskLock = CompletableDeferred<Unit>()
            diskLock.complete(Unit)
            sourceOfTruth.reader(request.key, diskLock).transform { response ->
              val data = response.dataOrNull()
              if (data == null || validator?.isValid(data) == false) {
                emit(StoreReadResponse.NoNewData(origin = response.origin))
              } else {
                emit(StoreReadResponse.Data(value = data, origin = response.origin))
              }
            }
          }
        emitAll(
          stream.transform { output: StoreReadResponse<Output> ->
            emit(output)
            if (output is StoreReadResponse.NoNewData && cachedToEmit == null) {
              // In the special case where fetcher returned no new data we actually want to
              // serve cache data (even if the request specified skipping cache and/or SoT)
              //
              // For stream(Request.cached(key, refresh=true)) we will return:
              // Cache
              // Source of truth
              // Fetcher - > Loading
              // Fetcher - > NoNewData
              // (future Source of truth updates)
              //
              // For stream(Request.fresh(key)) we will return:
              // Fetcher - > Loading
              // Fetcher - > NoNewData
              // Cache
              // Source of truth
              // (future Source of truth updates)
              memCache?.getIfPresent(request.key)?.let {
                emit(StoreReadResponse.Data(value = it, origin = StoreReadResponseOrigin.Cache))
              }
            }
          }
        )
      }
      .onEach {
        // whenever a value is dispatched, save it to the memory cache
        if (it.origin != StoreReadResponseOrigin.Cache) {
          it.dataOrNull()?.let { data -> memCache?.put(request.key, data) }
        }
      }

  override suspend fun clear(key: Key) {
    memCache?.invalidate(key)
    sourceOfTruth?.delete(key)
  }

  @ExperimentalStoreApi
  override suspend fun clear() {
    memCache?.invalidateAll()
    sourceOfTruth?.deleteAll()
  }

  /**
   * We want to stream from disk but also want to refresh. If requested or necessary.
   *
   * How it works: There are two flows: Fetcher: The flow we get for the fetching Disk: The flow we
   * get from the [SourceOfTruth]. Both flows are controlled by a lock for each so that we can start
   * the right one based on the request status or values we receive.
   *
   * Value is always returned from [SourceOfTruth] while the errors are dispatched from both the
   * `Fetcher` and [SourceOfTruth].
   *
   * There are two initialization paths:
   * 1) Request wants to skip disk cache: In this case, we first start the fetcher flow. When
   *    fetcher flow provides something besides an error, we enable the disk flow.
   * 2) Request does not want to skip disk cache: In this case, we first start the disk flow. If
   *    disk flow returns `null` or [StoreReadRequest.refresh] is set to `true`, we enable the
   *    fetcher flow. This ensures we first get the value from disk and then load from server if
   *    necessary.
   */
  private fun diskNetworkCombined(
    request: StoreReadRequest<Key>,
    sourceOfTruth: SourceOfTruthWithBarrier<Key, Network, Output, Local>,
  ): Flow<StoreReadResponse<Output>> {
    val diskLock = CompletableDeferred<Unit>()
    val networkLock = CompletableDeferred<Unit>()
    val networkFlow = createNetworkFlow(request, networkLock)
    val skipDiskCache = request.shouldSkipCache(CacheType.DISK)
    if (!skipDiskCache) {
      diskLock.complete(Unit)
    }
    val diskFlow =
      sourceOfTruth.reader(request.key, diskLock).onStart {
        // wait for disk to latch first to ensure it happens before network triggers.
        // after that, if we'll not read from disk, then allow network to continue
        if (skipDiskCache) {
          networkLock.complete(Unit)
        }
      }

    val requestKeyToFetcherName: MutableMap<Key, String?> = mutableMapOf()
    // we use a merge implementation that gives the source of the flow so that we can decide
    // based on that.
    return networkFlow.merge(diskFlow).transform {
      // left is Fetcher while right is source of truth
      when (it) {
        is Either.Left -> {
          // left, that is data from network
          val responseOrigin = it.value.origin as StoreReadResponseOrigin.Fetcher
          requestKeyToFetcherName[request.key] = responseOrigin.name

          val fallBackToSourceOfTruth =
            it.value is StoreReadResponse.Error && request.fallBackToSourceOfTruth

          if (
            it.value is StoreReadResponse.Data ||
              it.value is StoreReadResponse.NoNewData ||
              fallBackToSourceOfTruth
          ) {
            // Unlocking disk only if network sent data or reported no new data
            // so that fresh data request never receives new fetcher data after
            // cached disk data.
            // This means that if the user asked for fresh data but the network returned
            // no new data we will still unblock disk.
            diskLock.complete(Unit)
          }

          if (it.value !is StoreReadResponse.Data) {
            emit(it.value.swapType())
          }
        }

        is Either.Right -> {
          // right, that is data from disk
          when (val diskData = it.value) {
            is StoreReadResponse.Data -> {
              val responseOriginWithFetcherName =
                diskData.origin.let { origin ->
                  if (origin is StoreReadResponseOrigin.Fetcher) {
                    origin.copy(name = requestKeyToFetcherName[request.key])
                  } else {
                    origin
                  }
                }

              val diskValue = diskData.value
              val isValid =
                (validator == null && diskValue != null) ||
                  diskData.origin is StoreReadResponseOrigin.Fetcher ||
                  (diskValue != null && validator?.isValid(diskValue) ?: true)

              if (isValid) {
                @Suppress("UNCHECKED_CAST")
                val output =
                  diskData.copy(origin = responseOriginWithFetcherName) as StoreReadResponse<Output>
                emit(output)
              }
              // If the disk value is null
              // or refresh was requested
              // or the  disk value is not valid
              // then allow fetcher to start emitting values.
              if (request.refresh || diskData.value == null || !isValid) {
                networkLock.complete(Unit)
              }
            }

            is StoreReadResponse.Error -> {
              // disk sent an error, send it down as well
              emit(diskData)

              // If disk sent a read error, we should allow fetcher to start emitting
              // values since there is nothing to read from disk. If disk sent a write
              // error, we should NOT allow fetcher to start emitting values as we
              // should always wait for the read attempt.
              if (
                diskData is StoreReadResponse.Error.Exception &&
                  diskData.error is SourceOfTruth.ReadException
              ) {
                networkLock.complete(Unit)
              }
              // for other errors, don't do anything, wait for the read attempt
            }

            is StoreReadResponse.Initial,
            is StoreReadResponse.Loading,
            is StoreReadResponse.NoNewData -> {}
          }
        }
      }
    }
  }

  private fun createNetworkFlow(
    request: StoreReadRequest<Key>,
    networkLock: CompletableDeferred<Unit>?,
    piggybackOnly: Boolean = false,
  ): Flow<StoreReadResponse<Network>> {
    return fetcherController.getFetcher(request.key, piggybackOnly).onStart {
      // wait until disk gives us the go
      networkLock?.await()
      if (!piggybackOnly) {
        emit(StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()))
      }
    }
  }

  internal suspend fun write(key: Key, value: Output): StoreDelegateWriteResult =
    try {
      memCache?.put(key, value)
      sourceOfTruth?.write(key, converter.fromOutputToLocal(value))
      StoreDelegateWriteResult.Success
    } catch (error: Throwable) {
      StoreDelegateWriteResult.Error.Exception(error)
    }

  internal suspend fun latestOrNull(key: Key): Output? = fromMemCache(key) ?: fromSourceOfTruth(key)

  private suspend fun fromSourceOfTruth(key: Key) =
    sourceOfTruth?.reader(key, CompletableDeferred(Unit))?.map { it.dataOrNull() }?.first()

  private fun fromMemCache(key: Key) = memCache?.getIfPresent(key)

  companion object {
    private val logger =
      Logger.apply {
        setLogWriters(listOf(CommonWriter()))
        setTag("Store")
      }
  }
}
