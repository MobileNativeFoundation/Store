package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.impl.DefaultPagingCollector
import org.mobilenativefoundation.store.paging5.impl.PostReducerEffectHolder
import org.mobilenativefoundation.store.paging5.impl.RealDispatcher
import org.mobilenativefoundation.store.paging5.impl.RetriesRepository
import org.mobilenativefoundation.store.paging5.impl.effects.AppLoadPostReducerEffect
import org.mobilenativefoundation.store.paging5.impl.effects.LoadNextPostReducerEffect
import org.mobilenativefoundation.store.paging5.impl.effects.StartPostReducerEffect
import org.mobilenativefoundation.store.paging5.impl.effects.UpdateDataPostReducerEffect
import org.mobilenativefoundation.store.paging5.impl.effects.UserLoadPostReducerEffect
import kotlin.reflect.KClass

@ExperimentalStoreApi
class DispatcherBuilder<
    Id : Comparable<Id>,
    CK : StoreKey.Collection<Id>,
    SO :
    StoreData.Single<Id>,
    CA : Any,
    CE : Any,
    > internal constructor(
    private val initialKey: CK,
    private val childScope: CoroutineScope,
    private val dispatcherInjector: DispatcherInjector,
    private val anchorPosition: StateFlow<Id>,
    private val pagingConfig: PagingConfig,
    private val retriesRepository: RetriesRepository<Id, CK, SO>,
    private val jobCoordinator: JobCoordinator,
    private val logger: Logger?,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
) {
    private var middlewares: MutableList<PagingMiddleware<Id, CK, SO, CA, CE>> = mutableListOf()

    private val postReducerEffectHolder = PostReducerEffectHolder<Id, CK, SO, CE>()

    private lateinit var reducer: PagingReducer<Id, CK, SO, CE>

    private val queueManagerInjector: QueueManagerInjector<Id, CK> =
        object : QueueManagerInjector<Id, CK> {
            override var queueManager: QueueManager<Id, CK>? = null
        }

    fun middlewares(middlewares: List<PagingMiddleware<Id, CK, SO, CA, CE>>) =
        apply {
            this.middlewares.addAll(middlewares)
        }

    fun middleware(middleware: PagingMiddleware<Id, CK, SO, CA, CE>) =
        apply {
            this.middlewares.add(middleware)
        }

    fun reducer(reducer: PagingReducer<Id, CK, SO, CE>) = apply { this.reducer = reducer }

    fun defaultReducer(block: DefaultReducerBuilder<Id, CK, SO, CA, CE>.(childScope: CoroutineScope) -> Unit) =
        apply {
            val reducerBuilder =
                DefaultReducerBuilder<Id, CK, SO, CA, CE>(
                    initialKey,
                    childScope,
                    anchorPosition,
                    pagingConfig,
                    dispatcherInjector,
                    pagingStateManager,
                    logger,
                )
            block(reducerBuilder, childScope)
            val realReducer = reducerBuilder.build()
            this.reducer = realReducer
            this.queueManagerInjector.queueManager = realReducer.queueManager
        }

    fun <S : PagingState<Id, CK, SO, CE>, A : PagingAction> postReducerEffect(
        state: KClass<out PagingState<*, *, *, *>>,
        action: KClass<out A>,
        effect: PostReducerEffect<Id, CK, SO, CE, S, A>,
    ) = apply {
        this.postReducerEffectHolder.put(state, action, effect)
    }

    fun defaultStartPostReducerEffect(
        pagingSource: PagingSource<Id, CK, SO>,
        pagingCollector: PagingCollector<Id, CK, SO, CE> = DefaultPagingCollector(),
    ) = apply {
        val reducerEffect =
            StartPostReducerEffect(
                initialKey = initialKey,
                logger = logger,
                jobCoordinator = jobCoordinator,
                pagingStateManager = pagingStateManager,
                pagingCollector = pagingCollector,
                pagingSource = pagingSource,
                dispatcherInjector = dispatcherInjector,
            )

        this.postReducerEffect(
            PagingState.LoadingInitial::class,
            PagingAction.App.Start::class,
            reducerEffect,
        )
    }

    fun defaultAppLoadPostReducerEffect(
        pagingSource: PagingSource<Id, CK, SO>,
        pagingCollector: PagingCollector<Id, CK, SO, CE> = DefaultPagingCollector(),
    ) = apply {
        val reducerEffect =
            AppLoadPostReducerEffect(
                initialKey = initialKey,
                logger = logger,
                jobCoordinator = jobCoordinator,
                pagingStateManager = pagingStateManager,
                pagingCollector = pagingCollector,
                pagingSource = pagingSource,
                dispatcherInjector = dispatcherInjector,
            )

        this.postReducerEffectHolder.put(
            PagingState.Data.LoadingMore::class,
            PagingAction.App.Load::class,
            reducerEffect,
        )
    }

    fun defaultUserLoadPostReducerEffect(
        pagingSource: PagingSource<Id, CK, SO>,
        pagingCollector: PagingCollector<Id, CK, SO, CE> = DefaultPagingCollector(),
    ) = apply {
        val reducerEffect =
            UserLoadPostReducerEffect(
                initialKey = initialKey,
                logger = logger,
                jobCoordinator = jobCoordinator,
                pagingStateManager = pagingStateManager,
                pagingCollector = pagingCollector,
                pagingSource = pagingSource,
                dispatcherInjector = dispatcherInjector,
            )

        this.postReducerEffectHolder.put(
            PagingState.Data.LoadingMore::class,
            PagingAction.User.Load::class,
            reducerEffect,
        )
    }

    fun defaultUpdateDataPostReducerEffect() =
        apply {
            this.postReducerEffectHolder.put(
                PagingState.LoadingInitial::class,
                PagingAction.App.UpdateData::class,
                UpdateDataPostReducerEffect(
                    logger = logger,
                    queueManagerInjector = queueManagerInjector,
                ),
            )

            this.postReducerEffectHolder.put(
                PagingState.Data.LoadingMore::class,
                PagingAction.App.UpdateData::class,
                UpdateDataPostReducerEffect(
                    logger = logger,
                    queueManagerInjector = queueManagerInjector,
                ),
            )
        }

    fun defaultLoadNextPostReducerEffect() =
        apply {
            this.postReducerEffectHolder.put(
                PagingState.Data.Idle::class,
                PagingAction.App.UpdateData::class,
                LoadNextPostReducerEffect(
                    logger = logger,
                    queueManagerInjector = queueManagerInjector,
                ),
            )
        }

    fun defaultPostReducerEffects(
        pagingSource: PagingSource<Id, CK, SO>,
        pagingCollector: PagingCollector<Id, CK, SO, CE> = DefaultPagingCollector(),
    ) = apply {
        defaultStartPostReducerEffect(pagingSource, pagingCollector)
        defaultAppLoadPostReducerEffect(pagingSource, pagingCollector)
        defaultUserLoadPostReducerEffect(pagingSource, pagingCollector)
        defaultUpdateDataPostReducerEffect()
        defaultLoadNextPostReducerEffect()
    }

    fun build(): Dispatcher {
        return RealDispatcher(
            middlewares = middlewares,
            pagingStateManager = pagingStateManager,
            reducer = reducer,
            logger = logger,
            postReducerEffectHolder = postReducerEffectHolder,
            childScope = childScope,
        )
    }
}
