package com.dropbox.kmp.external.store4.impl.operators

import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import com.dropbox.kmp.external.store4.testutil.emitsExactlyAndCompletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.ExperimentalTime

@ExperimentalTime
@InternalCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
class MapIndexedKtTest {

    @Test
    fun mapIndexed() = runBlockingTest {
        flowOf(5, 6).mapIndexed { index, value -> index to value }
            .emitsExactlyAndCompletes(0 to 5, 1 to 6)
    }
}
