package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.paging.core.FetchingStrategy
import org.mobilenativefoundation.paging.core.Logger
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingBuffer
import org.mobilenativefoundation.paging.core.PagingConfig
import org.mobilenativefoundation.paging.core.PagingKey

class RealQueueManager<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    pagingConfigInjector: Injector<PagingConfig>,
    loggerInjector: OptionalInjector<Logger>,
    dispatcherInjector: Injector<Dispatcher<Id, K, P, D, E, A>>,
    private val fetchingStrategy: FetchingStrategy<Id, K, P, D>,
    private val pagingBuffer: PagingBuffer<Id, K, P, D>,
    private val anchorPosition: StateFlow<PagingKey<K, P>>,
    private val stateManager: StateManager<Id, K, P, D, E>,
) : QueueManager<K, P> {

    private val logger = lazy { loggerInjector.inject() }
    private val pagingConfig = lazy { pagingConfigInjector.inject() }
    private val dispatcher = lazy { dispatcherInjector.inject() }

    private val queue: ArrayDeque<PagingKey<K, P>> = ArrayDeque()

    override fun enqueue(key: PagingKey<K, P>) {
        logger.value?.log(
            """
            Enqueueing:
                Key: $key
        """.trimIndent()
        )

        queue.addLast(key)

        processQueue()
    }

    private fun processQueue() {
        while (queue.isNotEmpty() && fetchingStrategy.shouldFetch(
                anchorPosition = anchorPosition.value,
                prefetchPosition = stateManager.state.value.prefetchPosition,
                pagingConfig = pagingConfig.value,
                pagingBuffer = pagingBuffer,
            )
        ) {
            val nextKey = queue.removeFirst()

            logger.value?.log(
                """Dequeued:
                    Key: $nextKey
                """.trimMargin(),
            )

            dispatcher.value.dispatch(PagingAction.Load(nextKey))
        }
    }
}