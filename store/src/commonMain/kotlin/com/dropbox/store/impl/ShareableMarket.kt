@file:Suppress("UNCHECKED_CAST")

package com.dropbox.store.impl

import com.dropbox.store.ConflictResolution
import com.dropbox.store.Convenience
import com.dropbox.store.Converter
import com.dropbox.store.Fetch
import com.dropbox.store.Market
import com.dropbox.store.Market.Response.Companion.Origin
import com.dropbox.store.PostRequest
import com.dropbox.store.Store
import com.dropbox.store.Write
import com.dropbox.store.definition.AnyBroadcast
import com.dropbox.store.definition.AnyReadCompletions
import com.dropbox.store.definition.AnyWriteRequestQueue
import com.dropbox.store.definition.SomeBroadcast
import com.dropbox.store.definition.SomeWriteRequestQueue
import com.dropbox.store.operator.shareableMutableMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class ShareableMarket<Key : Any> internal constructor(
    private val scope: CoroutineScope,
    private val stores: List<Store<Key, *, *>>,
    private val conflictResolution: ConflictResolution<Key, *, *>
) : Market<Key> {

    private val readCompletions = shareableMutableMapOf<Key, AnyReadCompletions>()
    private val writeRequests = shareableMutableMapOf<Key, AnyWriteRequestQueue<Key>>()
    private val broadcasts = shareableMutableMapOf<Key, AnyBroadcast>()

    override suspend fun <Input : Any, Output : Any> read(request: Market.Request.Read<Key, Input, Output>): MutableSharedFlow<Market.Response<Output>> {
        val conflictsMightExistDeferred = scope.async { conflictsMightExist<Output>(request.key) }
        val conflictsMightExist = conflictsMightExistDeferred.await()

        val eagerlyResolveConflictsDeferred = scope.async {
            if (conflictsMightExist) {
                val isResolved = scope.async {
                    eagerlyResolveConflicts(
                        request.key,
                        request.request.post,
                        request.request.converter
                    )
                }
                isResolved.await()
            } else {
                true
            }
        }
        eagerlyResolveConflictsDeferred.await()
        val isResolved = eagerlyResolveConflictsDeferred.isCompleted

        addOrInitReadCompletions(request.key, request.onCompletions.toMutableList())

        val isBroadcasting = scope.async {
            if (notBroadcasting(request.key)) {
                startBroadcast<Output>(request.key)
                val emittedLatest = async { getAndEmitLatest(request.key, request.request) }
                emittedLatest.await()
            }
        }
        isBroadcasting.await()
        if (request.refresh && readNotInProgress(request.key)) {
            getAndEmitLatest(request.key, request.request)
        }
        return requireBroadcast(request.key)
    }

    private fun <Input : Any, Output : Any> updateWriteRequestQueue(
        key: Key,
        input: Input,
        created: Long,
        converter: Converter<Input, Output>
    ) {

        val writeRequestQueue = requireWriteRequestQueue<Input>(key)
        val outstandingWriteRequests: AnyWriteRequestQueue<Key> = ArrayDeque()
        writeRequests.access {

            for (writeRequest in writeRequestQueue) {
                if (writeRequest.created <= created) {
                    val output = converter(input)
                    val fetchResponse = Fetch.Result.Success(output)
                    (writeRequest.onCompletion as Fetch.OnCompletion<Output>).onSuccess(fetchResponse)
                } else {
                    outstandingWriteRequests.add(writeRequest)
                }
            }
            resetWriteRequestQueue(key, outstandingWriteRequests)
        }
    }

    private suspend fun <Input : Any, Output : Any> tryUpdateServer(request: Market.Request.Write<Key, Input, Output>): Boolean {
        return when (val result = tryPost<Input, Output>(request.key)) {
            is Fetch.Result.Success -> {
                updateWriteRequestQueue(
                    key = request.key,
                    input = result.value,
                    created = request.request.created,
                    converter = request.request.converter
                )

                val output = request.request.converter(result.value)
                val marketResponse = Market.Response.Success(output, origin = Origin.Remote)

                conflictResolution.deleteFailedWriteRecord(request.key)
                request.onCompletions.forEach { onCompletion -> onCompletion.onSuccess(marketResponse) }
                true
            }

            is Fetch.Result.Failure -> {
                conflictResolution.setLastFailedWriteTime(request.key, Clock.System.now().epochSeconds)
                false
            }
        }
    }

    override suspend fun <Input : Any, Output : Any> write(request: Market.Request.Write<Key, Input, Output>): Boolean {
        val broadcast = requireBroadcast<Output>(request.key)

        val responseLocalWrite = Market.Response.Success(request.input as Output, origin = Origin.LocalWrite)
        broadcast.emit(responseLocalWrite)
        stores.forEach { store -> (store.write as Write<Key, Input>).invoke(request.key, request.input) }

        addOrInitWriteRequest(request.key, request.request)

        return tryUpdateServer(request)
    }

    private fun <Output : Any> lastOrNull(key: Key): Output? {
        val last = getBroadcast<Output>(key)?.replayCache?.last()
        if (last is Market.Response.Success) {
            return last.value
        }
        return null
    }

    private suspend fun <Output : Any> load(key: Key) {
        val broadcast = requireBroadcast<Output>(key)

        for (store in stores) {
            try {
                val last = (store.read(key) as Flow<Output?>).lastOrNull()
                if (last != null) {
                    broadcast.emit(Market.Response.Success(last, origin = Origin.Store))
                    return
                }
            } catch (_: Throwable) {
            }
        }

        broadcast.emit(Market.Response.Loading)
    }

    private suspend fun <Input : Any, Output : Any> refresh(key: Key, request: Fetch.Request.Get<Key, Input, Output>) {

        try {
            val response = when (val result = request.get(key)) {
                null -> Market.Response.Empty
                else -> Market.Response.Success(result, origin = Origin.Remote)
            }

            if (response is Market.Response.Success) {
                stores.forEach { store -> (store.write as Write<Key, Output>).invoke(key, response.value) }

                readCompletions.access {
                    it[key]!!.forEach { anyOnCompletion ->
                        val onCompletion = anyOnCompletion as Market.Request.Read.OnCompletion<Output>
                        onCompletion.onSuccess(response)
                    }
                }
            }

            broadcasts.access {
                scope.launch {
                    it[key]!!.emit(response)
                }
            }
        } catch (throwable: Throwable) {
            val response = Market.Response.Failure(
                error = throwable,
                origin = Origin.Remote
            )

            broadcasts.access {
                scope.launch {
                    it[key]!!.emit(response)
                }
            }

            readCompletions.access {
                it[key]!!.forEach { anyOnCompletion ->
                    val onCompletion = anyOnCompletion as Market.Request.Read.OnCompletion<Output>
                    onCompletion.onFailure(response)
                }
            }
        }
    }

    private suspend fun <Input : Any, Output : Any> tryPost(key: Key): Fetch.Result<Input> {
        return try {
            val request = getLatestWriteRequest<Input, Output>(key)
            val last = lastOrNull<Input>(key) ?: throw Exception()
            return when (request.post(key, last)) {
                null -> Fetch.Result.Failure(Exception())
                else -> Fetch.Result.Success(last)
            }
        } catch (throwable: Throwable) {
            Fetch.Result.Failure(throwable)
        }
    }

    override suspend fun delete(key: Key): Boolean {
        for (store in stores) {
            try {
                when (store.delete(key)) {
                    true -> continue
                    false -> throw Exception()
                }
            } catch (throwable: Throwable) {
                return false
            }
        }
        val broadcast = requireBroadcast<Any>(key)
        val response = Market.Response.Empty
        broadcast.emit(response)
        return true
    }

    override suspend fun clear(): Boolean {
        for (store in stores) {
            try {
                store.clear.invoke()
            } catch (throwable: Throwable) {
                return false
            }
        }

        for (broadcast in broadcasts.access { it.values }) {
            val response = Market.Response.Empty
            broadcast.emit(response)
        }

        return true
    }

    private fun <Input : Any, Output : Any> getAndEmitLatest(
        key: Key,
        request: Fetch.Request.Get<Key, Input, Output>
    ) {
        scope.launch {
            val isBroadcasting = async { startIfNotBroadcasting<Output>(key) }
            isBroadcasting.await()
            load<Output>(key)
            refresh(key, request)
        }
    }

    private suspend fun <Output : Any> getOrSetBroadcast(key: Key): SomeBroadcast<Output> {

        val result = scope.async {
            if (broadcasts.access { it[key] == null }) {
                broadcasts.access {
                    it[key] = MutableSharedFlow(4)
                    scope.launch {
                        it[key]!!.emit(Market.Response.Loading)
                    }
                }
            }
        }


        result.await()
        return requireBroadcast(key)
    }

    private fun <Output : Any> getBroadcast(key: Key): SomeBroadcast<Output>? =
        broadcasts.access { it[key] } as? SomeBroadcast<Output>

    private fun <Output : Any> requireBroadcast(key: Key): SomeBroadcast<Output> =
        broadcasts.access { it[key]!! } as SomeBroadcast<Output>

    private fun <Input : Any> requireWriteRequestQueue(key: Key): SomeWriteRequestQueue<Key, Input> =
        writeRequests.access { it[key]!! } as SomeWriteRequestQueue<Key, Input>

    private fun broadcasting(key: Key) = broadcasts.access { it[key] != null }

    private fun addOrInitReadCompletions(key: Key, onCompletions: AnyReadCompletions) {
        if (readCompletions.access { it[key] != null }) {
            readCompletions.access { it[key]!!.addAll(onCompletions) }
        } else {
            readCompletions.access { it[key] = onCompletions }
        }
    }

    private fun resetWriteRequestQueue(key: Key, queue: AnyWriteRequestQueue<Key>) {
        writeRequests.access { it[key] = queue }
    }

    private fun <Input : Any, Output : Any> addOrInitWriteRequest(
        key: Key,
        request: Fetch.Request.Post<Key, Input, Output>
    ) {
        if (writeRequests.access { it[key] == null }) {
            writeRequests.access { it[key] = ArrayDeque() }
        }

        writeRequests.access { it[key]!!.add(request) }
    }

    private fun <Input : Any, Output : Any> getLatestWriteRequest(key: Key) =
        writeRequests.access { it[key]?.last() } as Fetch.Request.Post<Key, Input, Output>

    private fun readInProgress(key: Key) = readCompletions.access { it[key].isNullOrEmpty().not() }

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

    private suspend fun <Input : Any, Output : Any> eagerlyResolveConflicts(
        key: Key,
        request: PostRequest<Key, Input, Output>,
        converter: Converter<Output, Input>
    ): Boolean {

        return try {
            val lastStored = scope.async { lastStored<Output>(key) }
            if (lastStored.await() == null) {
                return false
            }

            val proxyOutput = lastStored.await()
            val input = converter(proxyOutput!!)
            val output = request.invoke(key, input)
            val resolved = output != null

            if (resolved) {
                conflictResolution.deleteFailedWriteRecord(key)

                updateWriteRequestQueue(
                    key = key,
                    input = input,
                    created = Clock.System.now().epochSeconds,
                    converter = { output!! }
                )
            }

            resolved
        } catch (throwable: Throwable) {
            false
        }
    }

    private suspend fun <Output : Any> conflictsMightExist(key: Key): Boolean {
        if (lastStored<Output>(key) == null) {
            return false
        }

        val lastWriteTime = scope.async { conflictResolution.getLastFailedWriteTime(key) }
        return lastWriteTime.await() != null || writeRequestsQueueIsNotEmpty(key)
    }

    private fun writeRequestsQueueIsEmpty(key: Key): Boolean {
        return writeRequests.access { it[key].isNullOrEmpty() }
    }

    @Convenience
    private fun readNotInProgress(key: Key) = readInProgress(key).not()

    @Convenience
    private fun notBroadcasting(key: Key) = broadcasting(key).not()

    @Convenience
    private suspend fun <Output : Any> startBroadcast(key: Key): SomeBroadcast<Output> {
        return getOrSetBroadcast(key)
    }

    @Convenience
    private fun <Output : Any> startIfNotBroadcasting(key: Key) {
        if (notBroadcasting(key)) {
            scope.launch {
                startBroadcast<Output>(key)
            }
        }
    }

    @Convenience
    private fun writeRequestsQueueIsNotEmpty(key: Key): Boolean {
        return writeRequestsQueueIsEmpty(key).not()
    }
}