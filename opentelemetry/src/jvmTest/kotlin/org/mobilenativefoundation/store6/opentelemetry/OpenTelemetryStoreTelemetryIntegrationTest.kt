@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest as coroutineRunTest

class OpenTelemetryStoreTelemetryIntegrationTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class Harness {
        val metricReader: InMemoryMetricReader = InMemoryMetricReader.create()
        val spanExporter: InMemorySpanExporter = InMemorySpanExporter.create()
        val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()

        fun close(): Unit = sdk.close()
    }

    @Test
    fun fetchInvalidateRefetchAndClearProduceTheExpectedSeries(): TestResult = runTest {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)
        var fetches = 0
        val store = store<TestKey, String> {
            fetcher { "value-${++fetches}" }
            telemetry(sink)
        }
        val key = TestKey("users", "user-1")

        try {
            assertEquals("value-1", store.get(key))
            store.invalidate(key)
            val refetched = store.stream(key, Freshness.MustBeFresh).first {
                it is StoreResult.Data<*>
            }
            assertEquals("value-2", assertIs<StoreResult.Data<String>>(refetched).value)
            store.clear(key)
        } finally {
            store.close()
        }

        val metrics = harness.metricReader.collectAllMetrics().associateBy { it.name }
        assertEquals(2L, metrics.getValue("store6.fetch.attempts").longSumData.points.single().value)
        assertEquals(2L, metrics.getValue("store6.fetch.duration").histogramData.points.single().count)
        assertEquals(1L, metrics.getValue("store6.invalidations").longSumData.points.single().value)
        assertEquals(1L, metrics.getValue("store6.clears").longSumData.points.single().value)
        val originKey = AttributeKey.stringKey("store6.origin")
        val serveOrigins = metrics.getValue("store6.serves").longSumData.points
            .associate { it.attributes.get(originKey) to it.value }
        assertTrue((serveOrigins["FETCHER"] ?: 0L) >= 2L, "serves=$serveOrigins")
        assertEquals(2, harness.spanExporter.finishedSpanItems.size)
        harness.close()
    }

    @Test
    fun aFailingFetchSettlesIntoAnErrorTypedDurationPoint(): TestResult = runTest {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val store = store<TestKey, String> {
            fetcher { error("review-gated diagnostic") }
            telemetry(sink)
        }
        val key = TestKey("users", "user-1")

        try {
            val result = store.stream(key, Freshness.MustBeFresh).first { it is StoreResult.Error }
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(result).error)
        } finally {
            store.close()
        }

        val metrics = harness.metricReader.collectAllMetrics()
        val errorTypeKey = AttributeKey.stringKey("error.type")
        val point = metrics.single { it.name == "store6.fetch.duration" }
            .histogramData.points.single()
        assertEquals("Fetch", point.attributes.get(errorTypeKey))
        assertTrue("review-gated diagnostic" !in metrics.toString())
        harness.close()
    }
}

// One file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
