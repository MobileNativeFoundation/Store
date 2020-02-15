package com.dropbox.android.external.store4.impl.operators

import com.dropbox.android.external.store4.testutil.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

class MapIndexedKtTest {
    val scope = TestCoroutineScope()
    @Test
    fun mapIndexed() = runBlockingTest {
        assertThat(flowOf(5,6).mapIndexed { index, value -> index to value })
            .emitsExactly(0 to 5, 1 to 6)
    }
}