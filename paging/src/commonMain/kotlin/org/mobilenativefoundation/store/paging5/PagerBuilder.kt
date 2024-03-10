package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.impl.DefaultJobCoordinator
import org.mobilenativefoundation.store.paging5.impl.RealAppDispatcher
import org.mobilenativefoundation.store.paging5.impl.RealPager
import org.mobilenativefoundation.store.paging5.impl.RetriesRepository

@ExperimentalStoreApi
class PagerBuilder<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any>(
    private val initialKey: CK,
    private val anchorPosition: StateFlow<Id>,
    private val pagingConfig: PagingConfig,
    initialState: PagingState<Id, CK, SO, CE> = PagingState.Initial(initialKey, null),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val childScope: CoroutineScope = scope + Job()
    private val pagingStateManager = PagingStateManager(initialState)
    private val jobCoordinator = DefaultJobCoordinator(childScope)
    private val retriesRepository = RetriesRepository<Id, CK, SO>()

    private lateinit var dispatcher: Dispatcher

    private var dispatcherInjector =
        object : DispatcherInjector {
            override var dispatch: (action: PagingAction) -> Unit = {}
        }

    fun dispatcher(
        logger: Logger? = null,
        block: DispatcherBuilder<Id, CK, SO, CA, CE>.() -> Unit,
    ) = apply {
        val builder =
            DispatcherBuilder<Id, CK, SO, CA, CE>(
                logger = logger,
                initialKey = initialKey,
                childScope = childScope,
                dispatcherInjector = dispatcherInjector,
                anchorPosition = anchorPosition,
                pagingConfig = pagingConfig,
                jobCoordinator = jobCoordinator,
                retriesRepository = retriesRepository,
                pagingStateManager = pagingStateManager,
            )

        block(builder)

        val dispatcher = builder.build()

        this.dispatcherInjector.dispatch = {
            dispatcher.dispatch(it)
        }

        this.dispatcher = dispatcher
    }

    fun build(): Pager<Id, CK, SO, CA, CE> {
        val appDispatcher = RealAppDispatcher(this.dispatcher)

        return RealPager(
            appDispatcher = appDispatcher,
            pagingStateManager = pagingStateManager,
        )
    }
}
