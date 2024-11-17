package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.operators.mapIndexed
import kotlin.test.Test
import kotlin.test.assertEquals

class MapIndexedTests {
    private val scope = TestScope()

    @Test
    fun mapIndexed() = scope.runTest {
        flowOf(5, 6).mapIndexed { index, value -> index to value }.test {
            assertEquals(0 to 5, awaitItem())
            assertEquals(1 to 6, awaitItem())
            awaitComplete()
        }
    }
}
