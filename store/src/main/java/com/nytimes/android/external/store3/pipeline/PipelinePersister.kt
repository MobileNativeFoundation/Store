package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.cache3.CacheBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


//lazy create a channelflow & start collecting disk
//
//return channelflow


class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output?>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {
    private val responseFlows = CacheBuilder
            .newBuilder()
            .build<StoreRequest<Key>, Pair<ConflatedBroadcastChannel<StoreResponse<Output>>, Channel<FetcherCommand>>>()


    /**
     * when a new request comes in we
     * 1. lazily create a channel for that key & make a call to disk and network TODO change to disk and/or network
     * 2. retain a handle to the fetcher commander allowing us to request new fetches later
     * 3. if user wants refresh then we trigger a fetcher command
     */
    @ExperimentalCoroutinesApi
    @Suppress("UNCHECKED_CAST")
    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> = channelFlow {
        val response = responseFlows
                .get(request) {
                    val sink = ConflatedBroadcastChannel<StoreResponse<Output>>()
                    val fetcherCommander = getData(request, this, sink)
                    sink to fetcherCommander
                }

        if (request.refresh) {
            response.second.offer(FetcherCommand.Fetch)
        }

        response.first.asFlow().collect { send(it) }
    }.share()

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
    private fun getData(
            request: StoreRequest<Key>,
            scope: CoroutineScope,
            sink: ConflatedBroadcastChannel<StoreResponse<Output>>
    ): Channel<FetcherCommand> {
        val fetcherCommands = Channel<FetcherCommand>(capacity = Channel.RENDEZVOUS)

        scope.launch {
            channelFlow {
                // used to control the disk flow so that we can stop/start it.
                val diskCommands = Channel<DiskCommand>(capacity = Channel.RENDEZVOUS)
                // used fetcherCommandsto control the network flow so that we can decide if we want to start it
                val fetcherCommands = Channel<FetcherCommand>(capacity = Channel.RENDEZVOUS)


                launch {
                    if (request.shouldSkipCache(CacheType.DISK)) {
                        fetcherCommands.send(FetcherCommand.Fetch)
                    } else {
                        diskCommands.send(DiskCommand.ReadFirst)
                    }
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
                            var fetcherOrigin: ResponseOrigin? = command.origin
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
            }.collect { sink.send(it) }
        }
        return fetcherCommands
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
