package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class KeyTrackerTest {
    private val scope1 = TestCoroutineScope()
    private val scope2 = TestCoroutineScope()

    private val subject = KeyTracker<Char>()

    @Test
    fun dontSkipInvalidations() = scope1.runBlockingTest {
        val collection = scope2.async {
            subject.keyFlow('b')
                    .take(2)
                    .toList()
        }
        scope2.advanceUntilIdle()
        assertThat(subject.activeKeyCount()).isEqualTo(1)
        scope2.pauseDispatcher()
        subject.invalidate('a')
        subject.invalidate('b')
        subject.invalidate('c')
        scope2.runCurrent()
        assertThat(collection.isCompleted).isTrue()
        assertThat(subject.activeKeyCount()).isEqualTo(0)
    }

    @Test
    fun multipleScopes() = scope1.runBlockingTest {
        val keys = 'a'..'z'
        val collections = keys.associate { key ->
            key to scope2.async {
                subject.keyFlow(key)
                        .take(2)
                        .toList()
            }
        }
        scope2.advanceUntilIdle()
        assertThat(subject.activeKeyCount()).isEqualTo(26)
        scope2.pauseDispatcher()
        keys.forEach {
            subject.invalidate(it)
        }
        scope2.advanceUntilIdle()
        collections.forEach { char, deferred ->
            assertThat(deferred.isCompleted).`as`("char $char").isTrue()
        }
        assertThat(subject.activeKeyCount()).isEqualTo(0)
    }

    @Test
    fun multipleObservers() = scope1.runBlockingTest {
        val collections = (0..4).map {
            scope2.async {
                subject.keyFlow('b')
                        .take(2)
                        .toList()
            }
        }
        scope2.advanceUntilIdle()
        assertThat(subject.activeKeyCount()).isEqualTo(1)
        scope2.pauseDispatcher()
        subject.invalidate('a')
        subject.invalidate('b')
        subject.invalidate('c')
        scope2.runCurrent()
        collections.forEachIndexed { index, collection ->
            assertThat(collection.isCompleted).`as`("collector $index").isTrue()
        }
        assertThat(subject.activeKeyCount()).isEqualTo(0)
    }

    @Test
    fun keyFlow_notCollected_shouldNotBeTracked() = scope1.runBlockingTest {
        val flow = subject.keyFlow('a')
        assertThat(subject.activeKeyCount()).isEqualTo(0)
        val collect = scope2.launch {
            flow.toList()
        }
        assertThat(subject.activeKeyCount()).isEqualTo(1)
        collect.cancelAndJoin()
        assertThat(subject.activeKeyCount()).isEqualTo(0)
    }

    @Test
    fun keyFlow_trackerShouldRefCount() = scope1.runBlockingTest {
        val flow = subject.keyFlow('a')
        assertThat(subject.activeKeyCount()).isEqualTo(0)
        val collect1 = scope2.launch {
            flow.toList()
        }
        val collect2 = scope2.launch {
            flow.toList()
        }
        assertThat(subject.activeKeyCount()).isEqualTo(1)
        collect1.cancelAndJoin()
        assertThat(subject.activeKeyCount()).isEqualTo(1)
        collect2.cancelAndJoin()
        assertThat(subject.activeKeyCount()).isEqualTo(0)
    }
}