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
package com.dropbox.flow.multicast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class StoreRealActorTest {

    /**
     * Intentionally not using test scope here to use real coroutines to ensure we don't get into
     * race conditions.
     */
    @Test
    fun closeOrder() {
        val didClose = AtomicBoolean(false)
        val actor = object : StoreRealActor<String>(CoroutineScope(EmptyCoroutineContext)) {
            var active = AtomicBoolean(false)
            override suspend fun handle(msg: String) {
                try {
                    active.set(true)
                    delay(100)
                } finally {
                    active.set(false)
                }
            }

            override fun onClosed() {
                assertThat(active.get()).isFalse()
                didClose.set(true)
            }
        }
        runBlocking {
            val firstMessageSent = CompletableDeferred<Unit>()
            val sender = GlobalScope.launch {
                repeat(500) {
                    try {
                        actor.send("a $it")
                        firstMessageSent.complete(Unit)
                    } catch (illegal: IllegalStateException) {
                    }
                }
            }
            firstMessageSent.await()
            actor.close()
            sender.join()
        }
        assertThat(didClose.get()).isTrue()
    }
}
