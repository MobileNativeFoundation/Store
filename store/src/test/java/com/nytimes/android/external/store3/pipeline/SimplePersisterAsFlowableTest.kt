package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.base.impl.BarCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions
import org.junit.Test
import java.lang.AssertionError

@FlowPreview
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

    @UseExperimental(FlowPreview::class)
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
        Assertions.assertThat(collectedValues.await()).isEqualTo(listOf("a", "b"))
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
                writer = { key : BarCode, value : String ->
                    written.add(value)
                },
                delete = {
                    TODO("not implemented")
                }
        ) to written
    }
}