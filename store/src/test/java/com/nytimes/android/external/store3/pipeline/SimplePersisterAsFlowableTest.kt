package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store4.legacy.BarCode
import com.nytimes.android.external.store4.SimplePersisterAsFlowable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class SimplePersisterAsFlowableTest {
    private val testScope = TestCoroutineScope()
    private val otherScope = TestCoroutineScope()
    private val barcode = BarCode("a", "b")
    @Test
    fun testSimple() = testScope.runBlockingTest {
        val (flowable , written)= create("a", "b")
        val read = flowable.flowReader(barcode).take(1).toCollection(mutableListOf())
        Assertions.assertThat(read).isEqualTo(listOf("a"))
    }

    @Test
    fun writeInvalidation() = testScope.runBlockingTest {
        val (flowable , written)= create("a", "b")
        flowable.flowWriter(BarCode("another", "value"), "dsa")
        val collectedFirst = CompletableDeferred<Unit>()
        var collectedValues = CompletableDeferred<List<String?>>()
        otherScope.launch {
            collectedValues.complete(flowable
                    .flowReader(barcode)
                    .onEach {
                        if (collectedFirst.isActive) {
                            collectedFirst.complete(Unit)
                        }
                    }
                    .take(2)
                    .toList())


        }
        collectedFirst.await()
        flowable.flowWriter(barcode, "x")
        testScope.advanceUntilIdle()
        otherScope.advanceUntilIdle()
        assertThat(collectedValues.await()).isEqualTo(listOf("a", "b"))
        assertThat(written).isEqualTo(listOf("dsa", "x"))
    }

    /**
     * invalidating inside the read should call itself again. This test fails if it cannot take 2
     * (in other words, writing inside the read failed to notify)
     */
    @Test
    fun nestedInvalidation() = testScope.runBlockingTest {
        val (flowable, written) = create("a", "b")
        flowable.flowReader(barcode).take(2).collect {
            flowable.flowWriter(barcode, "x")
        }
    }

    private fun create(
            vararg values : String
    ) : Pair<SimplePersisterAsFlowable<BarCode, String, String>, List<String>> {
        var readIndex = 0
        val written = mutableListOf<String>()
        return SimplePersisterAsFlowable<BarCode, String, String>(
                reader = {
                    if (readIndex >= values.size) {
                        throw AssertionError("should not've read this many")
                    }
                    values[readIndex++]
                },
                writer = { key: BarCode, value: String ->
                    written.add(value)
                },
                delete = {
                    TODO("not implemented")
                }
        ) to written
    }
}