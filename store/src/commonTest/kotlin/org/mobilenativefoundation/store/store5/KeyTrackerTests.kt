package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.test_utils.KeyTracker
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class KeyTrackerTests {
    private val scope1 = TestScope()
    private val scope2 = TestScope()

    private val subject = KeyTracker<Char>()

    @Test
    fun dontSkipInvalidations() =
        scope1.runTest {
            val collection =
                scope2.async {
                    subject.keyFlow('b')
                        .take(2)
                        .toList()
                }
            scope2.advanceUntilIdle()
            assertEquals(1, subject.activeKeyCount())
            scope2.advanceUntilIdle()
            subject.invalidate('a')
            subject.invalidate('b')
            subject.invalidate('c')
            scope2.advanceUntilIdle()
            assertEquals(true, collection.isCompleted)
            assertEquals(0, subject.activeKeyCount())
        }

    @Test
    fun multipleScopes() =
        scope1.runTest {
            val keys = 'a'..'z'
            val collections =
                keys.associate { key ->
                    key to
                        scope2.async {
                            subject.keyFlow(key)
                                .take(2)
                                .toList()
                        }
                }
            scope2.advanceUntilIdle()
            assertEquals(26, subject.activeKeyCount())

            scope2.advanceUntilIdle()
            keys.forEach {
                subject.invalidate(it)
            }
            scope2.advanceUntilIdle()

            collections.forEach { (_, deferred) ->
                assertEquals(true, deferred.isCompleted)
            }
            assertEquals(0, subject.activeKeyCount())
        }

    @Test
    fun multipleObservers() =
        scope1.runTest {
            val collections =
                (0..4).map {
                    scope2.async {
                        subject.keyFlow('b')
                            .take(2)
                            .toList()
                    }
                }
            scope2.advanceUntilIdle()
            assertEquals(1, subject.activeKeyCount())
            scope2.advanceUntilIdle()
            subject.invalidate('a')
            subject.invalidate('b')
            subject.invalidate('c')
            scope2.advanceUntilIdle()
            collections.forEach { collection ->
                assertEquals(true, collection.isCompleted)
            }
            assertEquals(0, subject.activeKeyCount())
        }

    @Test
    fun keyFlow_notCollected_shouldNotBeTracked() =
        scope1.runTest {
            val flow = subject.keyFlow('b')
            assertEquals(0, subject.activeKeyCount())
            scope2.launch {
                flow.collectIndexed { index, value ->
                    assertEquals(1, index)
                    assertEquals(Unit, value)
                    assertEquals(1, subject.activeKeyCount())
                    cancel()
                }
            }
            assertEquals(0, subject.activeKeyCount())
        }

    @Test
    fun keyFlow_trackerShouldRefCount() =
        scope1.runTest {
            val flow = subject.keyFlow('a')
            assertEquals(0, subject.activeKeyCount())
            scope2.launch {
                flow.collectIndexed { index, value ->
                    assertEquals(1, index)
                    assertEquals(Unit, value)
                    assertEquals(1, subject.activeKeyCount())
                    cancel()
                }
            }
            scope2.launch {
                flow.collectIndexed { index, value ->
                    assertEquals(1, index)
                    assertEquals(Unit, value)
                    assertEquals(1, subject.activeKeyCount())
                    cancel()
                }
            }

            assertEquals(0, subject.activeKeyCount())
        }
}
