package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
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
import org.mobilenativefoundation.store.store5.NetworkResult
import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.ReadRequest
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.concurrent.AnyThread
import org.mobilenativefoundation.store.store5.concurrent.StoreSafety
import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.PostRequest


typealias AnyReadCompletionsQueue = MutableList<OnMarketCompletion<*>>
typealias AnyWriteRequestQueue<Key> = ArrayDeque<WriteRequest<Key, *, *>>
typealias SomeWriteRequestQueue<Key, Input> = ArrayDeque<WriteRequest<Key, Input, *>>
typealias AnyBroadcast = MutableSharedFlow<MarketResponse<*>>
typealias SomeBroadcast<T> = MutableSharedFlow<MarketResponse<T>>
typealias SomeFlow<T> = Flow<MarketResponse<T>>

/**
 * Thread-safe [Market] implementation.
 * @param stores List of [Store]. Order matters! [RealMarket] executes [read], [write], and [delete] operations iteratively.
 * @param bookkeeper Implementation of [Bookkeeper]. Used in [read] to eagerly resolve conflicts and in [write] to persist write request failures.
 * @property readCompletions Thread-safe mapping of Key to [AnyReadCompletionsQueue]. Queue size is checked before handling a market read request. All completion handlers in queue are processed on market read response.
 * @property writeRequests Thread-safe mapping of Key to [AnyWriteRequestQueue], an alias of an [NetworkUpdater] queue. All requests are put in the queue. In [tryPost], we get the most recent request from the queue. Then we get the last value from [SomeBroadcast]. On response, write requests are handled based on their time of creation. On success, we reset the queue. On failure, we use [Bookkeeper.setTimestampLastFailedSync]. This map is only saved in memory. However, last failed write time and last local value will persist as long as the [Market] contains a persistent [Store].
 * @property broadcasts Thread-safe mapping of Key to [AnyBroadcast], an alias of a Mutable Shared Flow of [MarketResponse]. Callers of [read] receive [SomeBroadcast], which is the typed equivalent of [AnyBroadcast].
 */
class RealMarket<Key : Any, Input : Any, Output : Any> internal constructor(
    private val stores: List<Store<Key, Input, Output>>,
    private val bookkeeper: Bookkeeper<Key>,
    private val fetcher: NetworkFetcher<Key, Input>,
    private val updater: NetworkUpdater<Key, Input>,
) : Market<Key, Input, Output> {
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
    override suspend fun read(reader: ReadRequest<Key, Input, Output>): SomeFlow<Output> {
        mainLock.withLock {
            marketSecurity.getOrPut(reader.key) { StoreSafety() }
        }

        val conflictsMightExist = conflictsMightExist<Output>(reader.key)

        if (conflictsMightExist) {
            //TODO MIKE - check with Matt about flipping to updater below
            eagerlyResolveConflicts(reader.key, updater::post)
        }

        addOrInitReadCompletions(reader.key, reader.onCompletions.toMutableList())

        if (notBroadcasting(reader.key)) {
            startBroadcast<Output>(reader.key)
            getAndEmitLatest(reader.key, fetcher)
        } else if (reader.refresh && readNotInProgress(reader.key)) {
            getAndEmitLatest(reader.key, fetcher)
        } else if (readNotInProgress(reader.key)) {
            load<Output>(reader.key)
        }

        return flow {
            requireBroadcast<Output>(reader.key).collect {
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
        input: Input,
        created: Long,
    ) {
        val writeRequestQueue = requireWriteRequestQueue<Input>(key)
        val outstandingWriteRequests: AnyWriteRequestQueue<Key> = ArrayDeque()
        val storeSafety = requireStoreSafety(key)

        storeSafety.writeRequestsLightswitch.lock(storeSafety.writeRequestsLock)

        for (writeRequest in writeRequestQueue) {
            if (writeRequest.created <= created) {
                val fetchResponse = NetworkResult.Success(input)
                val marketResponse = MarketResponse.Success(input, origin = Origin.Network)

                updater.onCompletion.onSuccess(fetchResponse)

                writeRequest.onCompletions.forEach {
                    (it as OnMarketCompletion<Input>).onSuccess(marketResponse)
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
    private suspend fun tryUpdateServer(writer: WriteRequest<Key, Input, Output>): Boolean {
        return when (val result = tryPost(writer.key)) {
            is NetworkResult.Success -> {
                updateWriteRequestQueue(
                    key = writer.key,
                    input = result.value,
                    created = writer.created,
                )

                bookkeeper.delete(writer.key)
                true
            }

            is NetworkResult.Failure -> {
                bookkeeper.setTimestampLastFailedSync(writer.key, Clock.System.now().epochSeconds)
                false
            }
        }
    }

    @AnyThread
    override suspend fun write(writer: WriteRequest<Key, Input, Output>): Boolean {
        val broadcast = requireBroadcast<Output>(writer.key)
        val responseLocalWrite = MarketResponse.Success(
            writer.input as Output,
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
    private suspend fun <Output : Any> lastOrNull(key: Key): Output? {
        val last = getBroadcast<Output>(key)?.replayCache?.last()
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
    private suspend fun <Output : Any> load(key: Key) {
        val broadcast = requireBroadcast<Output>(key)

        stores.forEachIndexed { index, store ->
            try {
                storeLocks[index].lock()
                val last = (store.read(key) as Flow<Output?>).lastOrNull()
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
    private suspend fun <Input : Any> refresh(
        key: Key,
        fetcher: NetworkFetcher<Key, Input>
    ) {

        val storeSafety = requireStoreSafety(key)

        try {
            val response = when (val result = fetcher.get(key)) {
                null -> MarketResponse.Empty
                else -> MarketResponse.Success(result, origin = Origin.Network)
            }
            var firstOutput: Output? = null
            if (response is MarketResponse.Success) {

                (stores as List<Store<Key, Input, Output>>).forEachIndexed { index, store ->
                    storeLocks[index].withLock {
                        store.write(key, response.value)
                        if (index == 0) {
                            //we just wrote read should always succeed
                            firstOutput = store.read(key).last()!!
                        }
                    }
                }

                storeSafety.readCompletionsLightswitch.lock(storeSafety.readCompletionsLock)
                readCompletions[key]!!.forEach { anyOnCompletion ->
                    val onCompletion = anyOnCompletion as OnMarketCompletion<Output>
                    onCompletion.onSuccess(MarketResponse.Success(firstOutput!!, Origin.Network))
                }
                storeSafety.readCompletionsLightswitch.unlock(storeSafety.readCompletionsLock)
            }

            storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
            if (firstOutput == null)
                broadcasts[key]!!.emit(MarketResponse.Empty)
            else
                broadcasts[key]!!.emit(MarketResponse.Success(firstOutput, Origin.Network))
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
                val onCompletion = anyOnCompletion as OnMarketCompletion<Output>
                onCompletion.onFailure(response)
            }
            storeSafety.readCompletionsLightswitch.unlock(storeSafety.readCompletionsLock)
        }

        releaseStoreSecurity()
    }

    @AnyThread
    private suspend fun tryPost(key: Key): NetworkResult<Input> {
        return try {
            val writer = getLatestWriteRequest(key)

            return when (updater.post(key, writer.input)) {
                else -> NetworkResult.Success(writer.input)
            }
        } catch (throwable: Throwable) {
            NetworkResult.Failure(throwable)
        }
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
        fetcher: NetworkFetcher<Key, Input>
    ) {
        startIfNotBroadcasting<Input>(key)
        load<Input>(key)
        refresh(key, fetcher)
    }

    @AnyThread
    private suspend fun <Output : Any> getOrSetBroadcast(key: Key): SomeBroadcast<Output> {
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
    private suspend fun <Output : Any> getBroadcast(key: Key): SomeBroadcast<Output>? {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
        val broadcast = broadcasts[key] as? SomeBroadcast<Output>
        storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)
        releaseStoreSecurity()
        return broadcast
    }

    @AnyThread
    private suspend fun <Output : Any> requireBroadcast(key: Key): SomeBroadcast<Output> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.broadcastLightswitch.lock(storeSafety.broadcastLock)
        val broadcast = broadcasts[key] as SomeBroadcast<Output>
        storeSafety.broadcastLightswitch.unlock(storeSafety.broadcastLock)
        releaseStoreSecurity()
        return broadcast
    }

    @AnyThread
    private suspend fun <Input : Any> requireWriteRequestQueue(key: Key): SomeWriteRequestQueue<Key, Input> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.writeRequestsLightswitch.lock(storeSafety.writeRequestsLock)
        val writeRequestQueue = writeRequests[key] as SomeWriteRequestQueue<Key, Input>
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
    private suspend fun addOrInitWriteRequest(writer: WriteRequest<Key, *, *>) {
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
    private suspend fun getLatestWriteRequest(key: Key): WriteRequest<Key, Input, Output> {
        val storeSafety = requireStoreSafety(key)
        storeSafety.writeRequestsLock.lock()
        val writer = writeRequests[key]?.last() as WriteRequest<Key, Input, Output>
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
    private suspend fun <Output : Any> lastStored(key: Key): Output? {
        for (store in stores) {
            try {
                val last = (store.read(key) as Flow<Output?>).lastOrNull()
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
        request: PostRequest<Key, Input>
    ): Boolean {

        return try {
            val lastStored = lastStored<Input>(key) ?: return false
            val input = lastStored
            val output = request.invoke(key, input)
            val resolved = output != null

            if (resolved) {
                bookkeeper.delete(key)
                updateWriteRequestQueue(
                    key = key,
                    input = input,
                    created = Clock.System.now().epochSeconds
                )
            }

            resolved
        } catch (throwable: Throwable) {
            false
        }
    }

    @AnyThread
    private suspend fun <Output : Any> conflictsMightExist(key: Key): Boolean {
        if (lastStored<Output>(key) == null) {
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
    private suspend fun <Output : Any> startBroadcast(key: Key): SomeBroadcast<Output> {
        return getOrSetBroadcast(key)
    }

    @Convenience
    @AnyThread
    private suspend fun <Output : Any> startIfNotBroadcasting(key: Key) {
        if (notBroadcasting(key)) {
            startBroadcast<Output>(key)
        }
    }

    @Convenience
    @AnyThread
    private suspend fun writeRequestsQueueIsNotEmpty(key: Key): Boolean {
        return writeRequestsQueueIsEmpty(key).not()
    }
}
