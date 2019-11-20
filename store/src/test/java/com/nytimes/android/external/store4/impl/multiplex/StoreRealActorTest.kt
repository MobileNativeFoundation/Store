package com.nytimes.android.external.store4.impl.multiplex

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