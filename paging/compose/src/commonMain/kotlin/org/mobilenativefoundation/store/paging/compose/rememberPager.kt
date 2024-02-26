package org.mobilenativefoundation.store.paging.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.Pager
import org.mobilenativefoundation.store.paging5.PagingConfig
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.impl.DefaultPageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.impl.DefaultPageFetchingStrategy

@Composable
@ExperimentalStoreApi
inline fun <Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> rememberPager(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    initialKey: CK,
    anchorPosition: StateFlow<Id?> = MutableStateFlow(null),
    pagingConfig: PagingConfig = PagingConfig(),
    aggregator: PageAggregatingStrategy<Id, CK, SO> = DefaultPageAggregatingStrategy(),
    pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO> = DefaultPageFetchingStrategy(),
    noinline pagingSourceFactory: () -> PagingSource<Id, CK, SO>
): Pager<Id, CK, SO> = remember(coroutineScope, initialKey, anchorPosition, pagingConfig, aggregator, pageFetchingStrategy, pagingSourceFactory) {
    Pager.create(
        scope = coroutineScope,
        initialKey = initialKey,
        anchorPosition = anchorPosition,
        pagingConfig = pagingConfig,
        aggregator = aggregator,
        pageFetchingStrategy = pageFetchingStrategy,
        pagingSourceFactory = pagingSourceFactory
    )
}



@Composable
@ExperimentalStoreApi
fun <Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> Pager(
    pager: Pager<Id, CK, SO>,
    content: @Composable (data: Pager.PagingData<Id, SO>, error: Pager.PagingError?) -> Unit
) {

    val combinedFlow = combine(pager.data, pager.errors) { data, error ->
        data to error
    }

    val (data, error) = combinedFlow.collectAsState(Pager.PagingData(emptyList<SO>()) to null).value

    content(data, error)
}