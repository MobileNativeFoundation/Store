package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.operators.mapIndexed
import org.mobilenativefoundation.store.store5.test_utils.assertEmitsExactly
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapIndexedTests {
    private val scope = TestScope()

    @Test
    fun mapIndexed() =
        scope.runTest {
            assertEmitsExactly(flowOf(5, 6).mapIndexed { index, value -> index to value }, listOf(0 to 5, 1 to 6))
        }
}
