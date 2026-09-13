@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry.sample

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.opentelemetry.OpenTelemetryStoreTelemetry

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun runSample() {
    // The app owns the SDK: exporters, processors, sampling. This sample uses in-memory
    // exporters so it can assert on what was exported and stay dependency-free; a production
    // setup would use OTLP exporters behind batching processors (see the module README).
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

    var fetches = 0
    val store = store<ArticleKey, Article> {
        fetcher { key -> Article(key.id, "Article ${key.id} v${++fetches}") }
        telemetry(OpenTelemetryStoreTelemetry(sdk, emitSpans = true))
    }
    val key = ArticleKey("42")

    try {
        val first = store.get(key)
        check(first.title == "Article 42 v1") { "unexpected first fetch: ${first.title}" }

        val resident = store.get(key)
        check(resident.title == "Article 42 v1") { "unexpected resident serve: ${resident.title}" }

        store.invalidate(key)
        val refetched = store.stream(key, Freshness.MustBeFresh).first {
            it is StoreResult.Data<*>
        }
        val refetchedArticle = (refetched as StoreResult.Data<*>).value as Article
        check(refetchedArticle.title == "Article 42 v2") {
            "unexpected refetch: ${refetchedArticle.title}"
        }

        store.clear(key)

        val metrics = metricReader.collectAllMetrics().associateBy { it.name }
        check(
            metrics.keys == setOf(
                "store6.fetch.attempts",
                "store6.fetch.duration",
                "store6.serves",
                "store6.invalidations",
                "store6.clears",
            ),
        ) { "unexpected instruments: ${metrics.keys}" }
        check(metrics.getValue("store6.fetch.attempts").longSumData.points.single().value == 2L)
        check(metrics.getValue("store6.fetch.duration").histogramData.points.single().count == 2L)
        val spans = spanExporter.finishedSpanItems
        check(spans.size == 2 && spans.all { it.name == "store6.fetch" }) {
            "unexpected spans: $spans"
        }

        println("store6-opentelemetry sample: five instruments and ${spans.size} fetch spans exported")
        metrics.values.forEach(::println)
        spans.forEach(::println)
    } finally {
        store.close()
        // Shuts the providers down and stops their threads so the process can exit; without
        // it the Gradle run task would hang.
        sdk.close()
    }
}

private class ArticleKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("articles")

    override fun canonicalId(): String = id
}

private class Article(
    val id: String,
    val title: String,
)

private const val SAMPLE_TIMEOUT_MILLIS: Long = 20_000L
