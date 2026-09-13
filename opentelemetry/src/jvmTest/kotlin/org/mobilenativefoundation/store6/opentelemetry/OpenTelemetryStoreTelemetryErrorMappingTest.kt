@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryErrorMappingTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class ErrorCase(
        val name: String,
        val error: StoreError,
    )

    @Test
    fun allSixVariantsMapToTheirLiteralErrorTypeAndLeakNoDiagnostics() {
        val key = TestKey("users", "user-1")
        val diagnostics = listOf(
            "fetch diagnostic",
            "fetch cause diagnostic",
            "persistence diagnostic",
            "conversion diagnostic",
            "freshness diagnostic",
            "conflict diagnostic",
            "missing diagnostic",
        )
        val cases = listOf(
            ErrorCase(
                "Fetch",
                TestStoreResults.fetchError(diagnostics[0], IllegalStateException(diagnostics[1])),
            ),
            ErrorCase("Persistence", TestStoreResults.persistenceError(diagnostics[2])),
            ErrorCase("Conversion", TestStoreResults.conversionError(diagnostics[3])),
            ErrorCase("FreshnessUnsatisfiable", TestStoreResults.freshnessUnsatisfiable(diagnostics[4])),
            ErrorCase("Conflict", TestStoreResults.conflict(null, diagnostics[5])),
            ErrorCase("Missing", TestStoreResults.missing(key, diagnostics[6])),
        )
        assertEquals(cases.map { it.name }, cases.map { storeErrorType(it.error) })

        val metricReader = InMemoryMetricReader.create()
        val spanExporter = InMemorySpanExporter.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()
        val sink = OpenTelemetryStoreTelemetry(sdk, emitSpans = true)

        cases.forEachIndexed { index, case ->
            sink.onFetchFailed(key, case.error, (index + 1).milliseconds)
        }

        // Single snapshot: assert against one collection so a temporality change can never
        // make later reads empty.
        val metrics = metricReader.collectAllMetrics()
        val spans = spanExporter.finishedSpanItems

        val errorTypeKey = AttributeKey.stringKey("error.type")
        val points = metrics.single { it.name == "store6.fetch.duration" }.histogramData.points
        assertEquals(
            cases.map { it.name }.toSet(),
            points.map { it.attributes.get(errorTypeKey) }.toSet(),
        )
        points.forEach { point -> assertEquals(1L, point.count) }

        assertEquals(cases.size, spans.size)
        spans.forEach { span ->
            assertEquals(StatusCode.ERROR, span.status.statusCode)
            assertEquals("", span.status.description)
        }
        assertEquals(
            cases.map { it.name }.toSet(),
            spans.map { it.attributes.get(errorTypeKey) }.toSet(),
        )

        // Structured sweep of everything exported, plus rendered strings as a belt.
        val exportedValues = buildList {
            metrics.forEach { metric ->
                metric.longSumData.points.forEach { point ->
                    point.attributes.forEach { _, value -> add(value.toString()) }
                }
                metric.histogramData.points.forEach { point ->
                    point.attributes.forEach { _, value -> add(value.toString()) }
                }
            }
            spans.forEach { span ->
                add(span.name)
                add(span.status.description)
                span.attributes.forEach { _, value -> add(value.toString()) }
            }
        }
        val renderedExport = metrics.toString() + spans.toString()
        diagnostics.forEach { diagnostic ->
            assertFalse(exportedValues.any { diagnostic in it }, "diagnostic leaked: $diagnostic")
            assertFalse(diagnostic in renderedExport, "diagnostic leaked in rendering: $diagnostic")
        }
        sdk.close()
    }
}
