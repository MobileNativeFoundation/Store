@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryMetricsTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class Harness {
        val metricReader: InMemoryMetricReader = InMemoryMetricReader.create()
        val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build()

        fun metrics(): Map<String, MetricData> =
            metricReader.collectAllMetrics().associateBy { it.name }

        fun close(): Unit = sdk.close()
    }

    private val namespaceKey = AttributeKey.stringKey("store6.namespace")
    private val originKey = AttributeKey.stringKey("store6.origin")
    private val errorTypeKey = AttributeKey.stringKey("error.type")

    @Test
    fun countersRecordUnderTheV0NamesUnitsAndNamespaceAttribute() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchStarted(key)
        sink.onInvalidated(key)
        sink.onCleared(key)

        val metrics = harness.metrics()
        val attempts = metrics.getValue("store6.fetch.attempts")
        assertEquals("{attempt}", attempts.unit)
        val attemptsPoint = attempts.longSumData.points.single()
        assertEquals(2L, attemptsPoint.value)
        assertEquals("users", attemptsPoint.attributes.get(namespaceKey))
        val invalidations = metrics.getValue("store6.invalidations")
        assertEquals("{invalidation}", invalidations.unit)
        val invalidationsPoint = invalidations.longSumData.points.single()
        assertEquals(1L, invalidationsPoint.value)
        assertEquals("users", invalidationsPoint.attributes.get(namespaceKey))
        val clears = metrics.getValue("store6.clears")
        assertEquals("{clear}", clears.unit)
        val clearsPoint = clears.longSumData.points.single()
        assertEquals(1L, clearsPoint.value)
        assertEquals("users", clearsPoint.attributes.get(namespaceKey))
        assertEquals(INSTRUMENTATION_SCOPE_NAME, attempts.instrumentationScopeInfo.name)
        assertEquals(INSTRUMENTATION_SCOPE_VERSION, attempts.instrumentationScopeInfo.version)
        harness.close()
    }

    @Test
    fun servesRecordOneSeriesPerOrigin() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val key = TestKey("users", "user-1")

        Origin.entries.forEach { origin -> sink.onServe(key, origin) }
        sink.onServe(key, Origin.FETCHER)

        val serves = harness.metrics().getValue("store6.serves")
        assertEquals("{serve}", serves.unit)
        val byOrigin = serves.longSumData.points.associateBy { it.attributes.get(originKey) }
        assertEquals(Origin.entries.map { it.name }.toSet(), byOrigin.keys)
        assertEquals(2L, byOrigin.getValue("FETCHER").value)
        assertEquals(1L, byOrigin.getValue("MEMORY").value)
        harness.close()
    }

    @Test
    fun successDurationRecordsSecondsWithoutErrorTypeAndWithAdvisedBuckets() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 250.milliseconds)

        val duration = harness.metrics().getValue("store6.fetch.duration")
        assertEquals("s", duration.unit)
        val point = duration.histogramData.points.single()
        assertEquals(1L, point.count)
        assertEquals(0.25, point.sum, 1e-9)
        assertEquals("users", point.attributes.get(namespaceKey))
        assertNull(point.attributes.get(errorTypeKey))
        assertEquals(
            listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0),
            point.boundaries,
        )
        harness.close()
    }

    @Test
    fun extraAttributesMergeIntoEveryPointAndSinkOwnedKeysWin() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            extraAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("store6.store"), "users-store")
                .put(namespaceKey, "attacker-controlled")
                .build(),
        )
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onInvalidated(key)

        val metrics = harness.metrics()
        val storeKey = AttributeKey.stringKey("store6.store")
        listOf("store6.fetch.attempts", "store6.invalidations").forEach { name ->
            val point = metrics.getValue(name).longSumData.points.single()
            assertEquals("users-store", point.attributes.get(storeKey))
            assertEquals("users", point.attributes.get(namespaceKey))
        }
        harness.close()
    }

    @Test
    fun namespacesBeyondMaxNamespacesCoalesceToOverflow() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, maxNamespaces = 2)

        sink.onFetchStarted(TestKey("ns-a", "k"))
        sink.onFetchStarted(TestKey("ns-b", "k"))
        sink.onFetchStarted(TestKey("ns-c", "k"))
        sink.onFetchStarted(TestKey("ns-d", "k"))

        val points = harness.metrics()
            .getValue("store6.fetch.attempts").longSumData.points
        val byNamespace = points.associate { it.attributes.get(namespaceKey) to it.value }
        assertEquals(mapOf<String?, Long>("ns-a" to 1L, "ns-b" to 1L, "overflow" to 2L), byNamespace)
        harness.close()
    }

    @Test
    fun noopOpenTelemetryIsLegalAndRecordsNothing() {
        val sink = OpenTelemetryStoreTelemetry(OpenTelemetry.noop(), emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onServe(key, Origin.FETCHER)
        sink.onInvalidated(key)
        sink.onCleared(key)
        // Completing without an exception is the assertion; noop exports nothing.
    }
}
