package org.mobilenativefoundation.store.store5.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals

suspend inline fun <reified T> assertEmitsExactly(
    actual: Flow<T>,
    expected: List<T>,
) {
    val flow = actual.take(expected.size).toList()
    assertEquals(expected, flow)
}
