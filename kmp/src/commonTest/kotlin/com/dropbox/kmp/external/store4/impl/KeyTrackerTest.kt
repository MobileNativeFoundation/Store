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
package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.testutil.KeyTracker
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


@InternalCoroutinesApi
@ExperimentalCoroutinesApi
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
        assertEquals(1, subject.activeKeyCount())
        scope2.pauseDispatcher()
        subject.invalidate('a')
        subject.invalidate('b')
        subject.invalidate('c')
        scope2.runCurrent()
        assertTrue{ collection.isCompleted }
        assertEquals(0, subject.activeKeyCount())
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
        assertEquals(26, subject.activeKeyCount())
        scope2.pauseDispatcher()
        keys.forEach {
            subject.invalidate(it)
        }
        scope2.advanceUntilIdle()

        collections.forEach { (_, deferred) ->
            assertTrue { deferred.isCompleted }
        }
        assertEquals(0, subject.activeKeyCount())
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
        assertEquals(1, subject.activeKeyCount())
        scope2.pauseDispatcher()
        subject.invalidate('a')
        subject.invalidate('b')
        subject.invalidate('c')
        scope2.runCurrent()
        collections.forEach { collection ->
            assertTrue { collection.isCompleted }
        }
        assertEquals(0, subject.activeKeyCount())
    }

    @Test
    fun keyFlow_notCollected_shouldNotBeTracked() = scope1.runBlockingTest {
        val flow = subject.keyFlow('a')
        assertEquals(0, subject.activeKeyCount())
        val collect = scope2.launch {
            flow.toList()
        }
        assertEquals(1, subject.activeKeyCount())
        collect.cancelAndJoin()
        assertEquals(0, subject.activeKeyCount())
    }

    @Test
    fun keyFlow_trackerShouldRefCount() = scope1.runBlockingTest {
        val flow = subject.keyFlow('a')
        assertEquals(0, subject.activeKeyCount())
        val collect1 = scope2.launch {
            flow.toList()
        }
        val collect2 = scope2.launch {
            flow.toList()
        }
        assertEquals(1, subject.activeKeyCount())
        collect1.cancelAndJoin()
        assertEquals(1, subject.activeKeyCount())
        collect2.cancelAndJoin()
        assertEquals(0, subject.activeKeyCount())
    }
}
