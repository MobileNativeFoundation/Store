@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.DoubleHistogramBuilder
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongCounterBuilder
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterBuilder
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerBuilder
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.TimeUnit
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryRobustnessTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    /** Meter whose instruments throw on use; the tracer side stays the functioning no-op. */
    private class ThrowingMeterOpenTelemetry(
        private val delegate: OpenTelemetry = OpenTelemetry.noop(),
    ) : OpenTelemetry by delegate {
        override fun getMeterProvider(): MeterProvider = object : MeterProvider {
            override fun meterBuilder(instrumentationScopeName: String): MeterBuilder =
                object : MeterBuilder by delegate.meterProvider.meterBuilder(instrumentationScopeName) {
                    override fun setInstrumentationVersion(version: String): MeterBuilder = this

                    override fun setSchemaUrl(schemaUrl: String): MeterBuilder = this

                    override fun build(): Meter =
                        throwingMeter(delegate.meterProvider.get(instrumentationScopeName))
                }
        }

        private fun throwingMeter(delegate: Meter): Meter = object : Meter by delegate {
            override fun counterBuilder(name: String): LongCounterBuilder =
                object : LongCounterBuilder by delegate.counterBuilder(name) {
                    override fun setUnit(unit: String): LongCounterBuilder = this

                    override fun setDescription(description: String): LongCounterBuilder = this

                    override fun build(): LongCounter = object : LongCounter {
                        override fun add(value: Long): Unit = error("counter failure")

                        override fun add(value: Long, attributes: Attributes): Unit =
                            error("counter failure")

                        override fun add(value: Long, attributes: Attributes, context: Context): Unit =
                            error("counter failure")
                    }
                }

            override fun histogramBuilder(name: String): DoubleHistogramBuilder =
                object : DoubleHistogramBuilder by delegate.histogramBuilder(name) {
                    override fun setUnit(unit: String): DoubleHistogramBuilder = this

                    override fun setDescription(description: String): DoubleHistogramBuilder = this

                    override fun setExplicitBucketBoundariesAdvice(
                        bucketBoundaries: List<Double>,
                    ): DoubleHistogramBuilder = this

                    override fun build(): DoubleHistogram = object : DoubleHistogram {
                        override fun record(value: Double): Unit = error("histogram failure")

                        override fun record(value: Double, attributes: Attributes): Unit =
                            error("histogram failure")

                        override fun record(value: Double, attributes: Attributes, context: Context): Unit =
                            error("histogram failure")
                    }
                }
        }
    }

    @Test
    fun everyHookSwallowsThrowingInstruments() {
        val sink = OpenTelemetryStoreTelemetry(ThrowingMeterOpenTelemetry())
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onFetchFailed(key, TestStoreResults.fetchError("diagnostic"), 10.milliseconds)
        sink.onServe(key, Origin.FETCHER)
        sink.onInvalidated(key)
        sink.onCleared(key)
        // Completing without an exception is the assertion.
    }

    /** Tracer whose spanBuilder throws; the meter side stays the functioning no-op. */
    private class ThrowingTracerOpenTelemetry(
        private val delegate: OpenTelemetry = OpenTelemetry.noop(),
    ) : OpenTelemetry by delegate {
        override fun getTracerProvider(): TracerProvider = object : TracerProvider {
            override fun get(instrumentationScopeName: String): Tracer =
                Tracer { error("spanBuilder failure") }

            override fun get(
                instrumentationScopeName: String,
                instrumentationScopeVersion: String,
            ): Tracer = get(instrumentationScopeName)

            override fun tracerBuilder(instrumentationScopeName: String): TracerBuilder =
                object : TracerBuilder {
                    override fun setSchemaUrl(schemaUrl: String): TracerBuilder = this

                    override fun setInstrumentationVersion(
                        instrumentationScopeVersion: String,
                    ): TracerBuilder = this

                    override fun build(): Tracer = get(instrumentationScopeName)
                }
        }
    }

    @Test
    fun terminalHooksSwallowAThrowingSpanPath() {
        val sink = OpenTelemetryStoreTelemetry(ThrowingTracerOpenTelemetry(), emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onFetchFailed(key, TestStoreResults.fetchError("diagnostic"), 10.milliseconds)
        // Completing without an exception is the assertion; the no-op meter recorded fine.
    }

    /**
     * Wraps a real SDK tracer so setStatus throws after startSpan. Every fluent method the
     * sink calls is overridden to return this wrapper; delegation alone would let each
     * fluent call return the delegate and escape the wrapper before startSpan.
     */
    private class StatusThrowingOpenTelemetry(
        private val delegate: OpenTelemetry,
    ) : OpenTelemetry by delegate {
        override fun getTracerProvider(): TracerProvider = object : TracerProvider {
            override fun get(instrumentationScopeName: String): Tracer =
                wrap(delegate.tracerProvider.get(instrumentationScopeName))

            override fun get(
                instrumentationScopeName: String,
                instrumentationScopeVersion: String,
            ): Tracer = wrap(
                delegate.tracerProvider.get(instrumentationScopeName, instrumentationScopeVersion),
            )

            override fun tracerBuilder(instrumentationScopeName: String): TracerBuilder {
                val delegateBuilder = delegate.tracerProvider.tracerBuilder(instrumentationScopeName)
                return object : TracerBuilder {
                    override fun setSchemaUrl(schemaUrl: String): TracerBuilder {
                        delegateBuilder.setSchemaUrl(schemaUrl)
                        return this
                    }

                    override fun setInstrumentationVersion(
                        instrumentationScopeVersion: String,
                    ): TracerBuilder {
                        delegateBuilder.setInstrumentationVersion(instrumentationScopeVersion)
                        return this
                    }

                    override fun build(): Tracer = wrap(delegateBuilder.build())
                }
            }

            private fun wrap(tracer: Tracer): Tracer =
                Tracer { spanName -> ChainPreservingBuilder(tracer.spanBuilder(spanName)) }
        }

        private class ChainPreservingBuilder(
            private val delegate: SpanBuilder,
        ) : SpanBuilder by delegate {
            override fun setNoParent(): SpanBuilder {
                delegate.setNoParent()
                return this
            }

            override fun setSpanKind(spanKind: SpanKind): SpanBuilder {
                delegate.setSpanKind(spanKind)
                return this
            }

            override fun setStartTimestamp(startTimestamp: Long, unit: TimeUnit): SpanBuilder {
                delegate.setStartTimestamp(startTimestamp, unit)
                return this
            }

            override fun setAllAttributes(attributes: Attributes): SpanBuilder {
                delegate.setAllAttributes(attributes)
                return this
            }

            override fun <T> setAttribute(key: AttributeKey<T>, value: T?): SpanBuilder {
                delegate.setAttribute(key, value)
                return this
            }

            override fun startSpan(): Span = StatusThrowingSpan(delegate.startSpan())
        }

        private class StatusThrowingSpan(
            private val delegate: Span,
        ) : Span by delegate {
            override fun setStatus(statusCode: StatusCode): Span = error("status failure")

            override fun setStatus(statusCode: StatusCode, description: String): Span =
                error("status failure")
        }
    }

    @Test
    fun aThrowingSetStatusStillEndsTheSpan() {
        val spanExporter = InMemorySpanExporter.create()
        val realSdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()
        val sink = OpenTelemetryStoreTelemetry(StatusThrowingOpenTelemetry(realSdk), emitSpans = true)

        sink.onFetchFailed(
            TestKey("users", "user-1"),
            TestStoreResults.fetchError("diagnostic"),
            10.milliseconds,
        )

        // The wrapper's startSpan started a real span; the finally must have ended it even
        // though setStatus threw. UNSET status proves the throwing setStatus really ran
        // instead of the delegate's.
        val span = spanExporter.finishedSpanItems.single()
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        realSdk.close()
    }
}
