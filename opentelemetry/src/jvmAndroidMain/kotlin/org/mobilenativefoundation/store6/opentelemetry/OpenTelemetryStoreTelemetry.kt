package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * OpenTelemetry sink over the [StoreTelemetry] seam (a freeze candidate).
 *
 * One builder line installs it: `telemetry(OpenTelemetryStoreTelemetry(openTelemetry))`.
 * Metrics are always recorded; fetch spans are opt-in via [emitSpans]. The instrument names,
 * units, attributes, and span shape are the v0 vocabulary in `SIGNALS.md` (versioned but
 * experimental, and not the Store 6.1 wire format).
 *
 * Identity only: stored values never cross the telemetry seam, and this sink never emits
 * [StoreError] messages or causes. Key canonical ids appear only on spans and only when
 * [keyAttributeOnSpans] is enabled. Namespace attribute cardinality is bounded by
 * [maxNamespaces]; once the bound is reached, further namespaces record under the coalesced
 * value `overflow`.
 *
 * Providers are read from [openTelemetry] once, at construction, so the sink must be
 * constructed after the app's SDK; `OpenTelemetry.noop()` is a legal argument and records
 * nothing. After construction, handlers never throw: failures inside the OpenTelemetry
 * implementation are discarded so they cannot escape a telemetry handler. Handlers stay
 * non-blocking only if the supplied SDK configuration is non-blocking (batching span
 * processors, prompt samplers); that obligation transfers to the app the same way a logger
 * emit callback's promptness does. When telemetry is unset, the engine's null fast path
 * remains untouched.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class OpenTelemetryStoreTelemetry(
    openTelemetry: OpenTelemetry,
    private val emitSpans: Boolean = false,
    private val keyAttributeOnSpans: Boolean = false,
    private val maxNamespaces: Int = 512,
    private val extraAttributes: Attributes = Attributes.empty(),
) : StoreTelemetry {
    init {
        require(maxNamespaces > 0) { "maxNamespaces must be greater than zero, was $maxNamespaces." }
    }

    private val meter = openTelemetry.meterProvider
        .meterBuilder(INSTRUMENTATION_SCOPE_NAME)
        .setInstrumentationVersion(INSTRUMENTATION_SCOPE_VERSION)
        .build()

    private val fetchAttempts = meter.counterBuilder("store6.fetch.attempts")
        .setUnit("{attempt}")
        .setDescription("Fetch attempts observed at fetch-coroutine start.")
        .build()

    private val fetchDuration = meter.histogramBuilder("store6.fetch.duration")
        .setUnit("s")
        .setDescription("Duration of settled fetches.")
        .setExplicitBucketBoundariesAdvice(EXPLICIT_BUCKET_BOUNDARIES)
        .build()

    private val serves = meter.counterBuilder("store6.serves")
        .setUnit("{serve}")
        .setDescription("Successful public serves.")
        .build()

    private val invalidations = meter.counterBuilder("store6.invalidations")
        .setUnit("{invalidation}")
        .setDescription("Successful invalidations.")
        .build()

    private val clears = meter.counterBuilder("store6.clears")
        .setUnit("{clear}")
        .setDescription("Successful clears.")
        .build()

    private val tracer: Tracer? = if (emitSpans) {
        openTelemetry.tracerProvider
            .tracerBuilder(INSTRUMENTATION_SCOPE_NAME)
            .setInstrumentationVersion(INSTRUMENTATION_SCOPE_VERSION)
            .build()
    } else {
        null
    }

    private val attributesByNamespace = ConcurrentHashMap<String, NamespaceAttributes>()
    private val overflowAttributes = NamespaceAttributes(OVERFLOW_NAMESPACE, extraAttributes)

    override fun onFetchStarted(key: StoreKey) {
        try {
            fetchAttempts.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {
        try {
            if (!duration.isFinite() || duration.isNegative()) return
            val interned = namespaceAttributes(key)
            fetchDuration.record(duration.toDouble(DurationUnit.SECONDS), interned.base)
            recordSpan(key, interned, duration, errorType = null)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {
        try {
            if (!duration.isFinite() || duration.isNegative()) return
            val interned = namespaceAttributes(key)
            val errorType = storeErrorType(error)
            fetchDuration.record(
                duration.toDouble(DurationUnit.SECONDS),
                interned.byErrorType.getValue(errorType),
            )
            recordSpan(key, interned, duration, errorType)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {
        try {
            serves.add(1, namespaceAttributes(key).byOrigin.getValue(origin))
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onInvalidated(key: StoreKey) {
        try {
            invalidations.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onCleared(key: StoreKey) {
        try {
            clears.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    private fun namespaceAttributes(key: StoreKey): NamespaceAttributes {
        val namespace = key.namespace.value
        val cached = attributesByNamespace[namespace]
        if (cached != null) return cached
        // Approximate bound: concurrent first observations of distinct namespaces can each
        // pass this check, so the table can exceed maxNamespaces by the number of racing
        // namespaces. It is a memory and cardinality guard, not an exact quota.
        if (attributesByNamespace.size >= maxNamespaces) return overflowAttributes
        return attributesByNamespace.computeIfAbsent(namespace) {
            NamespaceAttributes(it, extraAttributes)
        }
    }

    private fun recordSpan(
        key: StoreKey,
        interned: NamespaceAttributes,
        duration: Duration,
        errorType: String?,
    ) {
        val tracer = tracer ?: return
        val endEpochNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
        val startEpochNanos = (endEpochNanos - duration.inWholeNanoseconds).coerceAtLeast(0L)
        val builder = tracer.spanBuilder(SPAN_NAME)
            .setNoParent()
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
            .setAllAttributes(
                if (errorType == null) interned.base else interned.byErrorType.getValue(errorType),
            )
        if (keyAttributeOnSpans) {
            builder.setAttribute(STORE_KEY_ATTRIBUTE, key.canonicalId())
        }
        val span = builder.startSpan()
        try {
            if (errorType != null) span.setStatus(StatusCode.ERROR)
        } finally {
            span.end(endEpochNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private class NamespaceAttributes(
    namespace: String,
    extraAttributes: Attributes,
) {
    val base: Attributes = Attributes.builder()
        .putAll(extraAttributes)
        .put(STORE_NAMESPACE_ATTRIBUTE, namespace)
        .build()

    // Built from the runtime enum, so an Origin grown in a future core version is covered
    // without recompiling this module.
    val byOrigin: Map<Origin, Attributes> = Origin.entries.associateWith { origin ->
        Attributes.builder()
            .putAll(base)
            .put(STORE_ORIGIN_ATTRIBUTE, origin.name)
            .build()
    }

    val byErrorType: Map<String, Attributes> = ERROR_TYPES.associateWith { errorType ->
        Attributes.builder()
            .putAll(base)
            .put(ERROR_TYPE_ATTRIBUTE, errorType)
            .build()
    }
}

// The six literals are the StoreError variant names, frozen for the 6.x major, and identical
// to the v0 error names in devtools/EVENTS.md.
internal fun storeErrorType(error: StoreError): String =
    when (error) {
        is StoreError.Fetch -> "Fetch"
        is StoreError.Persistence -> "Persistence"
        is StoreError.Conversion -> "Conversion"
        is StoreError.FreshnessUnsatisfiable -> "FreshnessUnsatisfiable"
        is StoreError.Conflict -> "Conflict"
        is StoreError.Missing -> "Missing"
    }

internal const val INSTRUMENTATION_SCOPE_NAME: String =
    "org.mobilenativefoundation.store6.opentelemetry"
// INSTRUMENTATION_SCOPE_VERSION is generated from VERSION_NAME by the
// generateInstrumentationScopeVersion Gradle task; see opentelemetry/build.gradle.kts.
internal const val SPAN_NAME: String = "store6.fetch"
internal const val OVERFLOW_NAMESPACE: String = "overflow"

private val STORE_NAMESPACE_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.namespace")
private val STORE_ORIGIN_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.origin")
private val STORE_KEY_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.key")
private val ERROR_TYPE_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("error.type")

private val ERROR_TYPES: List<String> = listOf(
    "Fetch",
    "Persistence",
    "Conversion",
    "FreshnessUnsatisfiable",
    "Conflict",
    "Missing",
)

// The semconv HTTP client duration boundaries; advice the SDK honors under the default
// aggregation, and a registered view overrides.
private val EXPLICIT_BUCKET_BOUNDARIES: List<Double> =
    listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)
