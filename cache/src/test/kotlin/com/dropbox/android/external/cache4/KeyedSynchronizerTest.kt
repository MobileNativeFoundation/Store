package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class KeyedSynchronizerTest {

    private val synchronizer = KeyedSynchronizer<String>()

    @Test
    fun `actions associated with the same key are mutually exclusive`() = runBlocking {
        val key = "a"
        var action1Started = false
        var action2Started = false
        var action3Started = false

        val actionTime = 50L

        // run action with synchronizer using the same key on 3 different threads concurrently
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor(key) {
                action1Started = true
                runBlocking { delay(actionTime) }
            }
        }
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor(key) {
                action2Started = true
                runBlocking { delay(actionTime) }
            }
        }
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor(key) {
                action3Started = true
                runBlocking { delay(actionTime) }
            }
        }

        delay(10)

        // action 1 starts immediately
        assertThat(action1Started).isTrue()
        assertThat(action2Started).isFalse()
        assertThat(action3Started).isFalse()

        delay(actionTime + 10)

        // action 1 completes, action 2 starts
        assertThat(action2Started).isTrue()
        assertThat(action3Started).isFalse()

        delay(actionTime + 10)

        // action 2 completes, action 3 starts
        assertThat(action3Started).isTrue()
    }

    @Test
    fun `actions associated with different keys can run concurrently`() = runBlocking {
        var action1Started = false
        var action2Started = false
        var action3Started = false

        val actionTime = 50L

        // run action with synchronizer using different keys on 3 different threads concurrently
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor("a") {
                action1Started = true
                runBlocking { delay(actionTime) }
            }
        }
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor("b") {
                action2Started = true
                runBlocking { delay(actionTime) }
            }
        }
        launch(newSingleThreadDispatcher()) {
            synchronizer.synchronizedFor("c") {
                action3Started = true
                runBlocking { delay(actionTime) }
            }
        }

        delay(10)

        // all 3 actions should have started
        assertThat(action1Started).isTrue()
        assertThat(action2Started).isTrue()
        assertThat(action3Started).isTrue()
    }

    @Test
    fun `a new action is queued after existing blocked actions using the same key from different thread`() =
        runBlocking {
            val key = "a"
            var action1Started = false
            var action2Started = false
            var action3Started = false

            val actionTime = 50L

            // start running action with synchronizer using the same key on 2 different threads concurrently
            launch(newSingleThreadDispatcher()) {
                // 1st action
                synchronizer.synchronizedFor(key) {
                    action1Started = true
                    runBlocking { delay(actionTime) }
                }

                // start running 3rd action once 1st action has finished
                launch(newSingleThreadDispatcher()) {
                    // 3rd action
                    synchronizer.synchronizedFor(key) {
                        action3Started = true
                        runBlocking { delay(actionTime) }
                    }
                }
            }
            launch(newSingleThreadDispatcher()) {
                // 2nd action
                synchronizer.synchronizedFor(key) {
                    action2Started = true
                    runBlocking { delay(actionTime) }
                }
            }

            delay(10)

            // action 1 starts immediately
            assertThat(action1Started).isTrue()
            assertThat(action2Started).isFalse()
            assertThat(action3Started).isFalse()

            delay(actionTime + 10)

            // action 1 completes, action 2 starts, action 3 is blocked
            assertThat(action2Started).isTrue()
            assertThat(action3Started).isFalse()

            delay(actionTime + 10)

            // action 2 completes, action 3 starts
            assertThat(action3Started).isTrue()
        }

    @Test
    fun `the next blocked action is unblocked when an action using the same key from another thread throws an exception`() =
        runBlocking {
            val key = "a"
            var action1Started = false
            var action2Started = false
            var action3Started = false

            val actionTime = 50L

            // start running action with synchronizer using the same key on 2 different threads concurrently
            launch(newSingleThreadDispatcher()) {
                // 1st action throws an exception
                runCatching {
                    synchronizer.synchronizedFor<String>(key) {
                        action1Started = true
                        runBlocking { delay(actionTime) }
                        throw IOException()
                    }
                }

                // start running 3rd action once 1st action has finished
                launch(newSingleThreadDispatcher()) {
                    // 3rd action
                    synchronizer.synchronizedFor(key) {
                        action3Started = true
                        runBlocking { delay(actionTime) }
                    }
                }
            }
            launch(newSingleThreadDispatcher()) {
                // 2nd action
                synchronizer.synchronizedFor(key) {
                    action2Started = true
                    runBlocking { delay(actionTime) }
                }
            }

            delay(10)

            // action 1 starts immediately
            assertThat(action1Started).isTrue()
            assertThat(action2Started).isFalse()
            assertThat(action3Started).isFalse()

            delay(actionTime + 10)

            // action 1 completes (failed), action 2 starts, action 3 is blocked
            assertThat(action2Started).isTrue()
            assertThat(action3Started).isFalse()

            delay(actionTime + 10)

            // action 2 completes, action 3 starts
            assertThat(action3Started).isTrue()
        }
}
