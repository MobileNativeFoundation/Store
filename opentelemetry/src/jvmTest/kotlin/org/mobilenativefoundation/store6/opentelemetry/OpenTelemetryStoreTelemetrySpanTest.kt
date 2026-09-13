@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetrySpanTest {
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
    fun spansAreDisabledByDefault() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 10.milliseconds)

        assertTrue(harness.spanExporter.finishedSpanItems.isEmpty())
        harness.close()
    }

    @Test
    fun spanCarriesExactDurationInternalKindNoParentUnsetStatusAndNoKeyByDefault() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 123.milliseconds)

        val span = harness.spanExporter.finishedSpanItems.single()
        assertEquals("store6.fetch", span.name)
        assertEquals(SpanKind.INTERNAL, span.kind)
        assertFalse(span.parentSpanContext.isValid)
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        assertEquals(123.milliseconds.inWholeNanoseconds, span.endEpochNanos - span.startEpochNanos)
        assertEquals("users", span.attributes.get(AttributeKey.stringKey("store6.namespace")))
        assertNull(span.attributes.get(AttributeKey.stringKey("store6.key")))
        assertEquals(INSTRUMENTATION_SCOPE_NAME, span.instrumentationScopeInfo.name)
        assertEquals(INSTRUMENTATION_SCOPE_VERSION, span.instrumentationScopeInfo.version)
        harness.close()
    }

    @Test
    fun keyAttributeAppearsOnlyWhenOptedIn() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            emitSpans = true,
            keyAttributeOnSpans = true,
        )

        sink.onFetchSucceeded(TestKey("users", "user-1"), 5.milliseconds)

        val span = harness.spanExporter.finishedSpanItems.single()
        assertEquals("user-1", span.attributes.get(AttributeKey.stringKey("store6.key")))
        harness.close()
    }

    @Test
    fun extraAttributesAndOverflowCoalescingApplyToSpans() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            emitSpans = true,
            maxNamespaces = 1,
            extraAttributes = Attributes.of(AttributeKey.stringKey("store6.store"), "users-store"),
        )

        sink.onFetchSucceeded(TestKey("ns-a", "k"), 5.milliseconds)
        sink.onFetchSucceeded(TestKey("ns-b", "k"), 5.milliseconds)

        val namespaceKey = AttributeKey.stringKey("store6.namespace")
        val spans = harness.spanExporter.finishedSpanItems
        assertEquals(
            listOf("ns-a", "overflow"),
            spans.map { it.attributes.get(namespaceKey) },
        )
        spans.forEach { span ->
            assertEquals("users-store", span.attributes.get(AttributeKey.stringKey("store6.store")))
        }
        harness.close()
    }

    @Test
    fun nonFiniteAndNegativeDurationsProduceNoSpanAndNoHistogramPoint() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchSucceeded(key, Duration.INFINITE)
        sink.onFetchSucceeded(key, (-5).milliseconds)

        assertTrue(harness.spanExporter.finishedSpanItems.isEmpty())
        assertTrue(
            harness.metricReader.collectAllMetrics().none { it.name == "store6.fetch.duration" },
        )
        harness.close()
    }
}
