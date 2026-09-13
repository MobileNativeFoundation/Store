@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class OpenTelemetryStoreTelemetryConcurrencyTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    @Test
    fun concurrentHooksAcrossNamespacesLoseNoCounts() {
        val metricReader = InMemoryMetricReader.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build()
        val sink = OpenTelemetryStoreTelemetry(sdk)
        val namespaces = listOf("ns-a", "ns-b")
        val threadsPerNamespace = 4
        val eventsPerThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(namespaces.size * threadsPerNamespace)
        val workers = namespaces.flatMap { namespace ->
            (1..threadsPerNamespace).map { workerIndex ->
                thread(isDaemon = true, name = "otel-$namespace-$workerIndex") {
                    val key = TestKey(namespace, "key-$workerIndex")
                    start.await()
                    repeat(eventsPerThread) { sink.onServe(key, Origin.MEMORY) }
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers did not finish in time")
        workers.forEach { worker -> worker.join(TimeUnit.SECONDS.toMillis(5)) }

        val namespaceKey = AttributeKey.stringKey("store6.namespace")
        val points = metricReader.collectAllMetrics()
            .single { it.name == "store6.serves" }
            .longSumData.points
        val byNamespace = points.associate { it.attributes.get(namespaceKey) to it.value }
        val expected = (threadsPerNamespace * eventsPerThread).toLong()
        assertEquals(mapOf<String?, Long>("ns-a" to expected, "ns-b" to expected), byNamespace)
        sdk.close()
    }
}
