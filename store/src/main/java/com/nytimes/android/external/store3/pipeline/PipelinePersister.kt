package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

class PipelinePersister<Key, Input, Output>(
    private val fetcher: PipelineStore<Key, Input>,
    private val reader: (Key) -> Flow<Output?>,
    private val writer: suspend (Key, Input) -> Unit,
    private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {
    @ExperimentalCoroutinesApi
    @Suppress("UNCHECKED_CAST")
    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> {
        return when {
            request.shouldSkipCache(CacheType.DISK) -> fetchSkippingCache(request)
            else -> diskNetworkCombined(request)
        }
    }

    /**
     * skipping cache, just delegate to the fetcher but update disk w/ any new data from fetcher
     */
    private fun fetchSkippingCache(request: StoreRequest<Key>): Flow<StoreResponse<Output>> {
        return fetcher.stream(request)
            .flatMapLatest { response: StoreResponse<Input> ->
                // explicit type is necessary for type resolution
                flow<StoreResponse<Output>> {
                    val data = response.dataOrNull()
                    if (data != null) {
                        // save into database first
                        writer(request.key, response.requireData())
                        // continue database data
                        var first = true
                        val readerFlow: Flow<StoreResponse<Output>> =
                            reader(request.key).mapNotNull {
                                it?.let {
                                    val origin = if (first) {
                                        first = false
                                        response.origin
                                    } else {
                                        ResponseOrigin.Persister
                                    }
                                    StoreResponse.Data(
                                        value = it,
                                        origin = origin
                                    )
                                }
                            }
                        emitAll(readerFlow)
                    } else {
                        emit(response.swapType())
                    }
                }
            }
    }

    /**
     * We want to stream from disk but also want to refresh. If requested or necessary.
     *
     * To do that, we need to see the first disk value and then decide to fetch or not.
     * in any case, we always return the Flow from reader.
     *
     * How it works:
     * We start by reading the disk. If first response from disk is `null` OR `request.refresh`
     * is set to `true`, we start fetcher flow.
     *
     * When fetcher emits data, if it is [StoreResponse.Error] or [StoreResponse.Loading], it
     * directly goes to the downstream.
     * If it is [StoreResponse.Data], we first stop the disk flow, write the new data to disk and
     * restart the disk flow. On restart, disk-flow sets the first emissions `origin` to the
     * `origin` set by the fetcher. subsequent reads use origin [ResponseOrigin.Persister].
     */
    private fun diskNetworkCombined(
        request: StoreRequest<Key>
    ): Flow<StoreResponse<Output>> = channelFlow {
        // used to control the disk flow so that we can stop/start it.
        val diskCommands = Channel<DiskCommand>(capacity = Channel.RENDEZVOUS)
        // used to control the network flow so that we can decide if we want to start it
        val fetcherCommands = Channel<FetcherCommand>(capacity = Channel.RENDEZVOUS)
        launch {
            // trigger first load
            diskCommands.send(DiskCommand.ReadFirst)
            fetcherCommands.consumeAsFlow().collectLatest {
                fetcher.stream(request).collect {
                    val data = it.dataOrNull()
                    if (data != null) {
                        try {
                            // stop disk first
                            val ack = CompletableDeferred<Unit>()
                            diskCommands.send(DiskCommand.Stop(ack))
                            ack.await()
                            writer(request.key, data)
                        } finally {
                            diskCommands.send(DiskCommand.Read(it.origin))
                        }
                    } else {
                        send(it.swapType<Output>())
                    }
                }
            }
        }
        diskCommands.consumeAsFlow().collectLatest { command ->
            when (command) {
                is DiskCommand.Stop -> {
                    command.ack.complete(Unit)
                }
                is DiskCommand.ReadFirst -> {
                    reader(request.key).collectIndexed { index, diskData ->
                        diskData?.let {
                            send(
                                StoreResponse.Data(
                                    value = diskData,
                                    origin = ResponseOrigin.Persister
                                )
                            )
                        }

                        if (index == 0 && (diskData == null || request.refresh)) {
                            fetcherCommands.send(
                                FetcherCommand.Fetch
                            )
                        }
                    }
                }
                is DiskCommand.Read -> {
                    var fetcherOrigin : ResponseOrigin? = command.origin
                    reader(request.key).collect { diskData ->
                        if (diskData != null) {
                            val origin = fetcherOrigin?.let {
                                fetcherOrigin = null
                                it
                            } ?: ResponseOrigin.Persister
                            send(
                                StoreResponse.Data(
                                    value = diskData,
                                    origin = origin
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun clear(key: Key) {
        fetcher.clear(key)
        delete?.invoke(key)
    }

    // used to control the disk flow when combined with network
    internal sealed class DiskCommand {
        object ReadFirst : DiskCommand()
        class Read(val origin: ResponseOrigin) : DiskCommand()
        class Stop(val ack: CompletableDeferred<Unit>) : DiskCommand()
    }

    // used to control the disk flow when combined with network
    internal sealed class FetcherCommand {
        object Fetch : FetcherCommand()
    }
}
