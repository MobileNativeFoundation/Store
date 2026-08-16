@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class StoreTelemetryLoggerJvmConcurrencyTest {
    @Test
    fun concurrentHandlersUseSeqAsCanonicalOrderingWhenEmitArrivalReorders() {
        val firstCanonicalIdEntered = CountDownLatch(1)
        val releaseFirstCanonicalId = CountDownLatch(1)
        val firstEmitEntered = CountDownLatch(1)
        val secondEmitEntered = CountDownLatch(1)
        var firstLine: String? = null
        var secondLine: String? = null
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = { line ->
                when {
                    " key=first" in line -> {
                        firstLine = line
                        firstEmitEntered.countDown()
                    }

                    " key=second" in line -> {
                        secondLine = line
                        secondEmitEntered.countDown()
                    }
                }
            },
        )
        val firstKey = TestKey("first") {
            // Sequence assignment precedes identity formatting. Hold seq=1 here so seq=2 can
            // reach the inline callback first without adding scheduling sleeps.
            firstCanonicalIdEntered.countDown()
            releaseFirstCanonicalId.await()
        }

        val firstWorker = thread(isDaemon = true, name = "store6-devtools-first") {
            logger.onInvalidated(firstKey)
        }
        assertTrue(firstCanonicalIdEntered.await(5, TimeUnit.SECONDS))

        val secondWorker = thread(isDaemon = true, name = "store6-devtools-second") {
            logger.onCleared(TestKey("second"))
        }
        val secondArrivedBeforeRelease = secondEmitEntered.await(5, TimeUnit.SECONDS)
        val firstHadNotArrived = firstEmitEntered.count == 1L
        releaseFirstCanonicalId.countDown()

        firstWorker.join(5_000)
        secondWorker.join(5_000)

        assertTrue(secondArrivedBeforeRelease)
        assertTrue(firstHadNotArrived)
        assertFalse(firstWorker.isAlive)
        assertFalse(secondWorker.isAlive)
        assertTrue(firstEmitEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            "store6 v0 seq=1 t_ms=0 evt=invalidate ns=users key=first",
            firstLine,
        )
        assertEquals(
            "store6 v0 seq=2 t_ms=0 evt=clear ns=users key=second",
            secondLine,
        )
    }

    private class TestKey(
        private val id: String,
        private val beforeCanonicalId: () -> Unit = {},
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("users")

        override fun canonicalId(): String {
            beforeCanonicalId()
            return id
        }
    }
}
