package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.cache3.CacheBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.actor


class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output?>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {
    private val responseFlows = CacheBuilder
            .newBuilder()
            .build<StoreRequest<Key>, Pair<SendChannel<StoreResponse<Output>>, SendChannel<Command>>>()

    /**
     * when a new request comes in we
     * 1. lazily create a channel for that key & make a call to disk and network
     * 2. retain a handle to the fetcher commander allowing us to request new fetches later
     * 3. if user wants refresh then we trigger a fetcher command
     */
    @ExperimentalCoroutinesApi
    @Suppress("UNCHECKED_CAST")
    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> = channelFlow {
        val response = responseFlows
                .get(request) {
                    val sink  = actor<StoreResponse<Output>>(capacity = Channel.RENDEZVOUS) {
                        consumeEach { response ->
                            try {
                                when (response) {
                                    is StoreResponse.Loading -> send(response)
                                    is StoreResponse.Data -> send(response)
                                    is StoreResponse.Error -> send(response)
                                }
                            }
                            catch (t: Throwable) {
                                send(StoreResponse.Error<Output>(t, ResponseOrigin.Persister) as StoreResponse<Output>)
                            }
                        }
                    }
                    val fetcherCommander: SendChannel<Command> = fetchData(request, this, sink)
                    sink to fetcherCommander
                }

        if (request.refresh) {
            response.second.offer(Command.Fetch)
        }
    }



  private fun fetchData(
          request: StoreRequest<Key>,
          scope: CoroutineScope,
          sink: SendChannel<StoreResponse<Output>>): SendChannel<Command> {
         val actor: SendChannel<Command> = scope.actor(capacity = Channel.UNLIMITED) {
            consumeEach { command ->
                try {
                    when (command) {
                        Command.ReadFirst -> readFirst(request, sink)
                        is Command.Read -> read(command, request, sink)
                        is Command.Stop -> command.ack.complete(Unit)
                        Command.Fetch -> fetch(request, sink)
                    }

                } catch (t: Throwable) {
                    sink.send(StoreResponse.Error(t, ResponseOrigin.Persister))
                }
            }
        }

        if (request.shouldSkipCache(CacheType.DISK)) {
            actor.offer(Command.Fetch)
        } else {
            actor.offer(Command.ReadFirst)
        }
        return actor
    }

    private suspend fun ActorScope<Command>.fetch(request: StoreRequest<Key>, sink: SendChannel<StoreResponse<Output>>) {
        fetcher.stream(request).collect {
            val data = it.dataOrNull()
            if (data != null) {
                try {
                    // stop disk first
                    val ack = CompletableDeferred<Unit>()
                    channel.send(Command.Stop(ack))
                    ack.await()
                    writer(request.key, data)
                } finally {
                    channel.send(Command.Read(it.origin))
                }
            } else {
                sink.offer(it.swapType<Output>())
            }
        }
    }

    private suspend fun read(command: Command.Read, request: StoreRequest<Key>, sink: SendChannel<StoreResponse<Output>>) {
        var fetcherOrigin: ResponseOrigin? = command.origin
        reader(request.key).collect { diskData ->
            if (diskData != null) {
                val origin = fetcherOrigin?.let {
                    fetcherOrigin = null
                    it
                } ?: ResponseOrigin.Persister
                sink.offer(
                        StoreResponse.Data(
                                value = diskData,
                                origin = origin
                        )
                )
            }
        }
    }

    private suspend fun ActorScope<Command>.readFirst(request: StoreRequest<Key>, sink: SendChannel<StoreResponse<Output>>) {
        reader(request.key).collectIndexed { index, diskData ->
            diskData?.let {
                sink.offer(
                        StoreResponse.Data(
                                value = diskData,
                                origin = ResponseOrigin.Persister
                        )
                )
            }

            if (index == 0 && (diskData == null || request.refresh)) {
                channel.send(Command.Fetch)
            }
        }
    }

    override suspend fun clear(key: Key) {
        fetcher.clear(key)
        delete?.invoke(key)
    }

    // used to control the disk flow when combined with network
    internal sealed class Command {
        object ReadFirst : Command()
        class Read(val origin: ResponseOrigin) : Command()
        class Stop(val ack: CompletableDeferred<Unit>) : Command()
        object Fetch : Command()
    }

}
