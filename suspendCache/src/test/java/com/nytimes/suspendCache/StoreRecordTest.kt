package com.nytimes.suspendCache

import com.com.nytimes.suspendCache.StoreRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@Suppress("UsePropertyAccessSyntax") // for isTrue()/isFalse()
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class StoreRecordTest {
    private val testScope = TestCoroutineScope()
    @Test
    fun precomputed() = testScope.runBlockingTest {
        val record = StoreRecord(
                key = "foo",
                loader = { TODO() },
                precomputedValue = "bar")
        assertThat(record.cachedValue()).isEqualTo("bar")
        assertThat(record.value()).isEqualTo("bar")
    }

    @Test
    fun fetched() = testScope.runBlockingTest {
        val record = StoreRecord("foo") { key ->
            assertThat(key).isEqualTo("foo")
            "bar"
        }
        assertThat(record.value()).isEqualTo("bar")
    }

    @Test
    fun fetched_multipleValueGet() = testScope.runBlockingTest {
        var runCount = 0
        val record = StoreRecord("foo") {
            runCount++
            "bar"
        }
        assertThat(record.value()).isEqualTo("bar")
        assertThat(record.value()).isEqualTo("bar")
        assertThat(runCount).isEqualTo(1)
    }

    @Test
    fun fetched_multipleValueGet_firstError() = testScope.runBlockingTest {
        var runCount = 0
        val errorMsg = "i'd like to fail"
        val record = StoreRecord("foo") {
            runCount++
            if (runCount == 1) {

                throw RuntimeException(errorMsg)
            } else {
                "bar"
            }
        }
        val first = runCatching {
            record.value()
        }
        assertThat(first.isFailure).isTrue()
        assertThat(first.exceptionOrNull()?.localizedMessage).isEqualTo(errorMsg)
        assertThat(record.value()).isEqualTo("bar")
        assertThat(runCount).isEqualTo(2)
    }

    @Test
    fun fetched_multipleValueGet_firstOneFails_delayed() = testScope.runBlockingTest {
        var runCount = 0
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val errorMsg = "i'd like to fail"
        val record = StoreRecord("foo") {
            runCount++
            if (runCount == 1) {
                return@StoreRecord firstResponse.await()
            } else {
                return@StoreRecord secondResponse.await()
            }
        }
        val first = async {
            record.value()
        }
        val second = async {
            record.value()
        }
        testScope.advanceUntilIdle()
        assertThat(first.isCompleted).isFalse()
        assertThat(second.isCompleted).isFalse()
        firstResponse.completeExceptionally(RuntimeException(errorMsg))

        assertThat(first.isCompleted).isTrue()
        assertThat(second.isCompleted).isFalse()

        assertThat(first.getCompletionExceptionOrNull()?.localizedMessage).isEqualTo(errorMsg)

        secondResponse.complete("bar")
        assertThat(second.await()).isEqualTo("bar")
        assertThat(runCount).isEqualTo(2)
    }

    @Test
    fun freshSimple_alreadyCached() = testScope.runBlockingTest {
        var runCount = 0
        val responses = listOf(
                "bar",
                "bar2"
        )
        val record = StoreRecord("foo") {
            val index = runCount
            runCount++
            return@StoreRecord responses[index]
        }
        assertThat(record.value()).isEqualTo("bar")
        assertThat(record.freshValue()).isEqualTo("bar2")
        assertThat(record.value()).isEqualTo("bar2")
    }

    @Test
    fun freshSimple_notCached() = testScope.runBlockingTest {
        val record = StoreRecord("foo") {
            "bar"
        }
        assertThat(record.freshValue()).isEqualTo("bar")
    }

    @Test
    fun fresh_multipleParallel() = testScope.runBlockingTest {
        val responses = listOf<CompletableDeferred<String>>(
                CompletableDeferred(),
                CompletableDeferred()
        )
        var runCount = 0
        val record = StoreRecord("foo") {
            val index = runCount
            runCount++
            responses[index].await()
        }
        val first = async {
            record.freshValue()
        }
        val second = async {
            record.freshValue()
        }
        assertThat(first.isActive).isTrue()
        assertThat(second.isActive).isTrue()
        responses[0].complete("bar")
        assertThat(first.await()).isEqualTo("bar")
        assertThat(second.isActive).isTrue()
        responses[1].complete("bar2")
        assertThat(second.await()).isEqualTo("bar2")
        assertThat(runCount).isEqualTo(2)
    }

    @Test
    fun fresh_multipleParallel_firstOneFails() = testScope.runBlockingTest {
        val responses = listOf(
                CompletableDeferred<String>(),
                CompletableDeferred()
        )
        var runCount = 0
        val record = StoreRecord("foo") {
            val index = runCount
            runCount++
            responses[index].await()
        }
        val first = async {
            record.freshValue()
        }
        val second = async {
            record.freshValue()
        }
        val errorMsg = "i'd like to fail"
        assertThat(first.isActive).isTrue()
        assertThat(second.isActive).isTrue()
        responses[0].completeExceptionally(RuntimeException(errorMsg))
        assertThat(first.getCompletionExceptionOrNull()?.localizedMessage).isEqualTo(errorMsg)
        assertThat(second.isActive).isTrue()
        responses[1].complete("bar")
        assertThat(second.await()).isEqualTo("bar")
        assertThat(runCount).isEqualTo(2)
    }
}
