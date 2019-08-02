package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@FlowPreview
@RunWith(JUnit4::class)
class FlowExtTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun sideCollect_instant() = testScope.runBlockingTest {
        val main = flowOf(1, 2, 3)
        val side = flowOf("a", "b", "c")
        val merged = mutableListOf<Any>()
        main.sideCollect(side) {
            merged.add(it)
        }.collect {
            merged.add(it)
        }
        assertThat(merged).isEqualTo(listOf("a", "b", "c", 1, 2, 3))
    }

    @Test
    fun sideCollect_sideDelayed() = testScope.runBlockingTest {
        val main = flowOf(1, 2, 3)
        val side = flowOf("a", "b", "c").delayFlow(10)
        val merged = mutableListOf<Any>()
        main.sideCollect(side) {
            merged.add(it)
        }.collect {
            merged.add(it)
        }
        assertThat(merged).isEqualTo(listOf(1, 2, 3))
    }

    @Test
    fun sideCollect_srcDelayed() = testScope.runBlockingTest {
        val main = flowOf(1, 2, 3).delayEach(10)
        val side = flowOf("a", "b", "c")
        val merged = mutableListOf<Any>()
        main.sideCollect(side) {
            merged.add(it)
        }.collect {
            merged.add(it)
        }
        assertThat(merged).isEqualTo(listOf("a", "b", "c", 1, 2, 3))
    }

    @Test
    fun sideCollect_interleaved() = testScope.runBlockingTest {
        val main = flow {
            emit(1)
            delay(10)
            emit(2)
            delay(20)
            emit(3)
        }
        val side = flow {
            delay(1)
            emit("a")
            delay(6)
            emit("b")
            delay(2000)
            emit("c") //
        }
        val merged = mutableListOf<Any>()
        main.sideCollect(side) {
            merged.add(it)
        }.collect {
            merged.add(it)
        }
        assertThat(merged).isEqualTo(listOf(1, "a", "b", 2, 3))
    }
}