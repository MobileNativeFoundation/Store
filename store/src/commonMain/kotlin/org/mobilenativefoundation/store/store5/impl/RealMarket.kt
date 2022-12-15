@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Convenience
import org.mobilenativefoundation.store.store5.ItemValidator
import org.mobilenativefoundation.store.store5.Market
import org.mobilenativefoundation.store.store5.MarketResponse
import org.mobilenativefoundation.store.store5.MarketResponse.Companion.Origin
import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.NetworkReadResult
import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.ReadRequest
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.concurrent.AnyThread
import org.mobilenativefoundation.store.store5.concurrent.StoreSafety
import org.mobilenativefoundation.store.store5.definition.PostRequest

typealias AnyReadCompletionsQueue = MutableList<OnMarketCompletion<*>>
typealias AnyWriteRequestQueue<Key> = ArrayDeque<WriteRequest<Key, *>>
typealias SomeWriteRequestQueue<Key, CommonRepresentation> = ArrayDeque<WriteRequest<Key, CommonRepresentation>>
typealias AnyBroadcast = MutableSharedFlow<MarketResponse<*>>
typealias SomeBroadcast<T> = MutableSharedFlow<MarketResponse<T>>
typealias SomeFlow<T> = Flow<MarketResponse<T>>

/**
 * Thread-safe [Market] implementation.
 * @param stores List of [Store]. Order matters! [RealMarket] executes [read], [write], and [delete] operations iteratively.
 * @param bookkeeper Implementation of [Bookkeeper]. Used in [read] to eagerly resolve conflicts and in [write] to persist write request failures.
 * @property readCompletions Thread-safe mapping of Key to [AnyReadCompletionsQueue]. Queue size is checked before handling a market read request. All completion handlers in queue are processed on market read response.
 * @property writeRequests Thread-safe mapping of Key to [AnyWriteRequestQueue], an alias of an [NetworkUpdater] queue. All requests are put in the queue. In [postLatest], we get the most recent request from the queue. Then we get the last value from [SomeBroadcast]. On response, write requests are handled based on their time of creation. On success, we reset the queue. On failure, we use [Bookkeeper.setTimestampLastFailedSync]. This map is only saved in memory. However, last failed write time and last local value will persist as long as the [Market] contains a persistent [Store].
 * @property broadcasts Thread-safe mapping of Key to [AnyBroadcast], an alias of a Mutable Shared Flow of [MarketResponse]. Callers of [read] receive [SomeBroadcast], which is the typed equivalent of [AnyBroadcast].
 */
class RealMarket<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> internal constructor(
    private val stores: List<Store<Key, NetworkRepresentation, CommonRepresentation>>,
    private val bookkeeper: Bookkeeper<Key>,
    private val fetcher: NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse>,
    private val updater: NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse>,
) : Market<Key, NetworkRepresentation, CommonRepresentation, NetworkWriteResponse> {
    private val readCompletions = mutableMapOf<Key, AnyReadCompletionsQueue>()
    private val writeRequests = mutableMapOf<Key, AnyWriteRequestQueue<Key>>()
    private val broadcasts = mutableMapOf<Key, AnyBroadcast>()

    private val mainLock = Mutex()
    private val storeLocks = stores.map { Mutex() }
    private val marketSecurity = mutableMapOf<Key, StoreSafety>()

    /**
     * Reads from [Market].
     * Gets the latest value from network if first request or [ReadRequest.refresh].
     * Validates [Store] items if [ReadRequest] contains [ItemValidator].
     */
    @AnyThread
    override suspend fun read(reader: ReadRequest<Key, NetworkRepresentation, CommonRepresentation>): SomeFlow<CommonRepresentation> {
        mainLock.withLock {
            marketSecurity.getOrPut(reader.key) { StoreSafety() }
        }

        val conflictsMightExist = conflictsMightExist<CommonRepresentation>(reader.key)

        if (conflictsMightExist) {
            eagerlyResolveConflicts(reader.key, fetcher::post)
        }

        addOrInitReadCompletions(reader.key, reader.onCompletions.toMutableList())

        if (notBroadcasting(reader.key)) {
            startBroadcast<CommonRepresentation>(reader.key)
            getAndEmitLatest(reader.key, fetcher)
        } else if (reader.refresh && readNotInProgress(reader.key)) {
            getAndEmitLatest(reader.key, fetcher)
        } else if (readNotInProgress(reader.key)) {
            load<CommonRepresentation>(reader.key)
        }

        return flow {
            requireBroadcast<CommonRepresentation>(reader.key).collect {
                if (it is MarketResponse.Success &&
                    it.origin == MarketResponse.Companion.Origin.Store &&
                    reader.validator?.isValid(it.value) == false
                ) {
                    getAndEmitLatest(reader.key, fetcher)
                } else {
                    emit(it)
                }
            }
        }
    }

    @AnyThread
    private suspend fun updateWriteRequestQueue(
        key: Key,
        created: Long,
    ) {
        val writeRequestQueue = requireWriteRequestQueue(key)
        val outstandingWriteRequests: AnyWriteRequestQueue<Key> = ArrayDeque()
        val storeSafety = requireStoreSafety(key)

        storeSafety.writeRequestsLightswitch.lock(storeSafety.writeRequestsLock)

        for (writeRequest in writeRequestQueue) {
            if (writeRequest.created <= created) {

                // TODO()
                val networkWriteResponse = updater.converter(writeRequest.input)
                val networkReadResult = NetworkReadResult.Success(networkWriteResponse)
                val marketResponse = MarketResponse.Success(writeRequest.input, origin = Origin.Network)

                updater.onCompletion.onSuccess(networkReadResult)

                writeRequest.onCompletions.forEach {
                    it.onSuccess(marketResponse)
                }
            } else {
                outstandingWriteRequests.add(writeRequest)
            }
        }

        writeRequests[key] = outstandingWriteRequests

        storeSafety.writeRequestsLightswitch.unlock(storeSafety.writeRequestsLock)

        releaseStoreSecurity()
    }

    @AnyThread
    private suspend fun tryUpdateServer(writer: WriteRequest<Key, CommonRepresentation>): NetworkWriteResponse {
        val networkWriteResponse = postLatest(writer.key)
        val isOk = updater.responseValidator(networkWriteResponse)

        if (isOk) {
            updateWriteRequestQueue(
                key = writer.key,
                created = writer.created,
            )

            bookkeeper.delete(writer.key)
        } else {
            bookkeeper.setTimestampLastFailedSync(writer.key, Clock.System.now().epochSeconds)
        }

        return networkWriteResponse
    }

    @AnyThread
    override suspend fun write(writer: WriteRequest<Key, CommonRepresentation>): NetworkWriteResponse {
        val broadcast = requireBroadcast<CommonRepresentation>(writer.key)

        val responseLocalWrite = MarketResponse.Success(
            writer.input,
            origin = Origin.LocalWrite
        )
        broadcast.emit(responseLocalWrite)
        stores.forEachIndexed { index, store ->
            storeLocks[index].withLock {
                store.write(writer.key, writer.input)
            }
        }

        addOrInitWriteRequest(writer)

        return tryUpdateServer(writer)
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> lastOrNull(key: Key): CommonRepresentation? {
        val last = getBroadcast<CommonRepresentation>(key)?.replayCache?.last()
        if (last is MarketResponse.Success) {
            return last.value
        }
        return null
    }

    /**
     * Tries reading from [Store] until [MarketResponse.Success] or each [Store] in [stores] is checked.
     * Swallows read exceptions.
     * Emits [MarketResponse.Loading] if no [Store] has a value for [key].
     */
    @AnyThread
    private suspend fun <CommonRepresentation : Any> load(key: Key) {
        val broadcast = requireBroadcast<CommonRepresentation>(key)

        stores.forEachIndexed { index, store ->
            try {
                storeLocks[index].lock()
                val last = (store.read(key) as Flow<CommonRepresentation?>).lastOrNull()
                storeLocks[index].unlock()
                if (last != null) {
                    broadcast.emit(MarketResponse.Success(last, origin = Origin.Store))
                    return
                }
            } catch (_: Throwable) {
            }
        }

        broadcast.emit(MarketResponse.Loading)
    }

    @AnyThread
    private suspend fun refresh(
        key: Key,
        fetcher: NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse>
    ) {

        val storeSafety = requireStoreSafety(key)

        try {

            val response = when (val result = fetcher.get(key)) {
                null -> MarketResponse.Empty
                else -> {
                    val commonRepresentation = fetcher.converter(result)
                    MarketResponse.Success(commonRepresentation, origin = Origin.Network)
                }
            }

            if (response is MarketResponse.Success) {
                stores.forEachIndexed { index, store ->
                    storeLocks[index].withLock {
                        store.write(key, response.value)
                    }
                }

                storeSafety.readCompletionsLightswitch.lock(storeSafety.readCompletionsLock)
                readCompletions[key]!!.forEach { anyOnCompletion ->
                    val onCompletion = anyOnCompletion as OnMarketCompletion<CommonRepresentation>
                    onCompletion.onSuccess(response)
                }
                storeSafety.readCompletionsLightswitch.unlock(storeSafety.readCompletionsLock)
            }

            storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
            broadcasts[key]!!.emit(response)
            storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)
        } catch (throwable: Throwable) {
            val response = MarketResponse.Failure(
                error = throwable, origin = MarketResponse.Companion.Origin.Network
            )

            storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
            broadcasts[key]!!.emit(response)
            storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)

            storeSafety.readCompletionsLightswitch.lock(storeSafety.readCompletionsLock)
            readCompletions[key]!!.forEach { anyOnCompletion ->
                val onCompletion = anyOnCompletion as OnMarketCompletion<CommonRepresentation>
                onCompletion.onFailure(response)
            }
            storeSafety.readCompletionsLightswitch.unlock(storeSafety.readCompletionsLock)
        }

        releaseStoreSecurity()
    }

    @AnyThread
    private suspend fun postLatest(key: Key): NetworkWriteResponse {
        val writer = getLatestWriteRequest(key)
        return updater.post(key, writer.input)
    }

    @AnyThread
    override suspend fun delete(key: Key): Boolean {
        stores.forEachIndexed { index, store ->
            try {
                storeLocks[index].withLock {
                    if (!store.delete(key)) {
                        throw Exception()
                    }
                }
            } catch (throwable: Throwable) {
                return false
            }
        }

        val broadcast = requireBroadcast<Any>(key)
        val response = MarketResponse.Empty
        broadcast.emit(response)
        return true
    }

    @AnyThread
    override suspend fun delete(): Boolean {
        stores.forEach { store ->
            try {
                storeLocks.forEach { it.lock() }
                if (!store.clear()) {
                    throw Exception()
                }
                storeLocks.forEach { it.unlock() }
            } catch (throwable: Throwable) {
                return false
            }
        }

        for (broadcast in broadcasts.values) {
            val response = MarketResponse.Empty
            broadcast.emit(response)
        }

        return true
    }

    @AnyThread
    private suspend fun getAndEmitLatest(
        key: Key,
        fetcher: NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse>
    ) {
        startIfNotBroadcasting<CommonRepresentation>(key)
        load<CommonRepresentation>(key)
        refresh(key, fetcher)
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> getOrSetBroadcast(key: Key): SomeBroadcast<CommonRepresentation> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLock.withLock {
            if (broadcasts[key] == null) {
                broadcasts[key] = MutableSharedFlow(10)
                broadcasts[key]!!.emit(MarketResponse.Loading)
            }
        }
        releaseStoreSecurity()
        return requireBroadcast(key)
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> getBroadcast(key: Key): SomeBroadcast<CommonRepresentation>? {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
        val broadcast = broadcasts[key] as? SomeBroadcast<CommonRepresentation>
        storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)
        releaseStoreSecurity()
        return broadcast
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> requireBroadcast(key: Key): SomeBroadcast<CommonRepresentation> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
        val broadcast = broadcasts[key] as SomeBroadcast<CommonRepresentation>
        storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)
        releaseStoreSecurity()
        return broadcast
    }

    @AnyThread
    private suspend fun requireWriteRequestQueue(key: Key): SomeWriteRequestQueue<Key, CommonRepresentation> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.writeRequestsLightswitch.lock(storeSafety.writeRequestsLock)
        val writeRequestQueue = writeRequests[key] as SomeWriteRequestQueue<Key, CommonRepresentation>
        storeSafety.writeRequestsLightswitch.unlock(storeSafety.writeRequestsLock)
        releaseStoreSecurity()
        return writeRequestQueue
    }

    @AnyThread
    private suspend fun broadcasting(key: Key): Boolean {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLock.lock()
        val isBroadcasting = broadcasts[key] != null
        storeSafety.broadcastLock.unlock()
        releaseStoreSecurity()
        return isBroadcasting
    }

    @AnyThread
    private suspend fun addOrInitReadCompletions(key: Key, onCompletions: AnyReadCompletionsQueue) {
        val storeSafety = requireStoreSafety(key)
        storeSafety.readCompletionsLock.withLock {
            if (readCompletions[key] != null) {
                readCompletions[key]!!.addAll(onCompletions)
            } else {
                readCompletions[key] = onCompletions
            }
        }
        releaseStoreSecurity()
    }

    @AnyThread
    private suspend fun addOrInitWriteRequest(writer: WriteRequest<Key, *>) {
        val storeSafety = requireStoreSafety(writer.key)
        storeSafety.writeRequestsLock.withLock {
            if (writeRequests[writer.key] == null) {
                writeRequests[writer.key] = ArrayDeque()
            }
            writeRequests[writer.key]!!.add(writer)
        }
        releaseStoreSecurity()
    }

    @AnyThread
    private suspend fun getLatestWriteRequest(key: Key): WriteRequest<Key, CommonRepresentation> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.writeRequestsLock.lock()
        val writer = writeRequests[key]?.last() as WriteRequest<Key, CommonRepresentation>
        storeSafety.writeRequestsLock.unlock()
        releaseStoreSecurity()
        return writer
    }

    @AnyThread
    private suspend fun readInProgress(key: Key): Boolean {
        val storeSafety = requireStoreSafety(key)

        storeSafety.readCompletionsLightswitch.lock(storeSafety.readCompletionsLock)
        val inProgress = readCompletions[key].isNullOrEmpty().not()
        storeSafety.readCompletionsLightswitch.unlock(storeSafety.readCompletionsLock)
        releaseStoreSecurity()
        return inProgress
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> lastStored(key: Key): CommonRepresentation? {
        for (store in stores) {
            try {
                val last = (store.read(key) as Flow<CommonRepresentation?>).lastOrNull()
                if (last != null) {
                    return last
                }
            } catch (_throwable: Throwable) {
                continue
            }
        }
        return null
    }

    @AnyThread
    private suspend fun eagerlyResolveConflicts(
        key: Key,
        request: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
    ): Boolean {

        return try {
            val lastStored = lastStored<CommonRepresentation>(key) ?: return false
            val networkWriteResponse = request.invoke(key, lastStored)

            val resolved = updater.responseValidator(networkWriteResponse)

            if (resolved) {
                bookkeeper.delete(key)
                updateWriteRequestQueue(
                    key = key,
                    created = Clock.System.now().epochSeconds
                )
            }

            resolved
        } catch (throwable: Throwable) {
            false
        }
    }

    @AnyThread
    private suspend fun <CommonRepresentation : Any> conflictsMightExist(key: Key): Boolean {
        if (lastStored<CommonRepresentation>(key) == null) {
            return false
        }

        val lastWriteTime = bookkeeper.getTimestampLastFailedSync(key)
        return lastWriteTime != null || writeRequestsQueueIsNotEmpty(key)
    }

    @AnyThread
    private suspend fun writeRequestsQueueIsEmpty(key: Key): Boolean {
        val storeSafety = requireStoreSafety(key)
        storeSafety.writeRequestsLightswitch.lock(storeSafety.writeRequestsLock)
        val isEmpty = writeRequests[key].isNullOrEmpty()
        storeSafety.writeRequestsLightswitch.unlock(storeSafety.writeRequestsLock)
        releaseStoreSecurity()
        return isEmpty
    }

    @AnyThread
    private suspend fun requireStoreSafety(key: Key): StoreSafety {
        mainLock.lock()
        return marketSecurity[key]!!
    }

    @AnyThread
    private fun releaseStoreSecurity() {
        mainLock.unlock()
    }

    @Convenience
    @AnyThread
    private suspend fun readNotInProgress(key: Key) = readInProgress(key).not()

    @Convenience
    @AnyThread
    private suspend fun notBroadcasting(key: Key) = broadcasting(key).not()

    @Convenience
    @AnyThread
    private suspend fun <CommonRepresentation : Any> startBroadcast(key: Key): SomeBroadcast<CommonRepresentation> {
        return getOrSetBroadcast(key)
    }

    @Convenience
    @AnyThread
    private suspend fun <CommonRepresentation : Any> startIfNotBroadcasting(key: Key) {
        if (notBroadcasting(key)) {
            startBroadcast<CommonRepresentation>(key)
        }
    }

    @Convenience
    @AnyThread
    private suspend fun writeRequestsQueueIsNotEmpty(key: Key): Boolean {
        return writeRequestsQueueIsEmpty(key).not()
    }
}
