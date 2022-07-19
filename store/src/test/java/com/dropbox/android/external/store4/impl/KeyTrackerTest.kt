/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.testutil.KeyTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.pauseDispatcher
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

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

        collections.forEach { (_, deferred) ->
            assertThat(deferred.isCompleted).isTrue()
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
        collections.forEach { collection ->
            assertThat(collection.isCompleted).isTrue()
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
