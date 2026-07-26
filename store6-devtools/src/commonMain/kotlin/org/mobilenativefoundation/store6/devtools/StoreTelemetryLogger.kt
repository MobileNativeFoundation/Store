package org.mobilenativefoundation.store6.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Structured logger sink over the [StoreTelemetry] seam (a freeze candidate).
 *
 * One builder line installs it: `telemetry(StoreTelemetryLogger())`. Output is one line per event
 * with stable v0 field names (see `EVENTS.md`; versioned but experimental, and not the 6.1 wire
 * format):
 * `store6 v0 seq=<n> t_ms=<elapsed> evt=<kind> ns=<ns> key=<id> [origin=..] [fetch_ms=..] [error=..]`.
 *
 * Identity values containing structural delimiters or line controls are quoted and escaped.
 * `error` is one of six literal v0 [StoreError] names; messages are review-gated diagnostics and
 * are never log fields. [emit] is invoked synchronously on the handler's caller thread. It must
 * return promptly and be thread-safe; failures are discarded so they cannot escape a telemetry
 * handler. The default `println` emitter is for development diagnostics and may perform platform
 * I/O.
 *
 * Concurrent handlers receive unique sequence values through a [MutableStateFlow] compare-and-set
 * update without application-owned atomics, locks, or channels. Formatting and emitter delivery
 * are deliberately not serialized, so callback arrival may differ from sequence order; `seq` is
 * the canonical ordering key. Installed cost is one formatted string plus synchronous callback
 * work per event. When telemetry is unset, the engine's null fast path remains untouched.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class StoreTelemetryLogger(
    private val label: String = "store6",
    timeSource: TimeSource = TimeSource.Monotonic,
    private val emit: (String) -> Unit = ::println,
) : StoreTelemetry {
    init {
        require(label.isNotBlank()) { "label must not be blank." }
        require(label.none(::isReservedLabelCharacter)) {
            "label must not contain whitespace, control characters, quote, equals, or backslash."
        }
    }

    private val start = timeSource.markNow()
    private val seq = MutableStateFlow(0L)

    override fun onFetchStarted(key: StoreKey): Unit = log("fetch_started", key)

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ): Unit = log("fetch_succeeded", key, "fetch_ms=${duration.inWholeMilliseconds}")

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ): Unit =
        log(
            "fetch_failed",
            key,
            "fetch_ms=${duration.inWholeMilliseconds}",
            "error=${storeErrorV0Name(error)}",
        )

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ): Unit = log("serve", key, "origin=$origin")

    override fun onInvalidated(key: StoreKey): Unit = log("invalidate", key)

    override fun onCleared(key: StoreKey): Unit = log("clear", key)

    private fun log(
        kind: String,
        key: StoreKey,
        vararg fields: String,
    ) {
        val n = seq.updateAndGet { it + 1 }
        val line = buildString {
            append(label).append(" v0")
            append(" seq=").append(n)
            append(" t_ms=").append(start.elapsedNow().inWholeMilliseconds)
            append(" evt=").append(kind)
            append(" ns=").append(quoteIfNeeded(key.namespace.value))
            append(" key=").append(quoteIfNeeded(key.canonicalId()))
            for (field in fields) append(' ').append(field)
        }
        try {
            emit(line)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }
}

internal fun quoteIfNeeded(value: String): String =
    if (
        value.none {
            it == ' ' ||
                it == '"' ||
                it == '=' ||
                it == '\\' ||
                isUnsafeIdentityCharacter(it)
        }
    ) {
        value
    } else {
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (isUnsafeIdentityCharacter(character)) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }
    }

private fun isUnsafeIdentityCharacter(character: Char): Boolean =
    character.code in 0x00..0x1F ||
        character.code in 0x7F..0x9F ||
        character == '\u2028' ||
        character == '\u2029'

internal fun storeErrorV0Name(error: StoreError): String =
    when (error) {
        is StoreError.Fetch -> "Fetch"
        is StoreError.Persistence -> "Persistence"
        is StoreError.Conversion -> "Conversion"
        is StoreError.FreshnessUnsatisfiable -> "FreshnessUnsatisfiable"
        is StoreError.Conflict -> "Conflict"
        is StoreError.Missing -> "Missing"
    }

private fun isReservedLabelCharacter(character: Char): Boolean =
    character.isWhitespace() ||
        character.code in 0x00..0x1F ||
        character.code in 0x7F..0x9F ||
        character == '"' ||
        character == '=' ||
        character == '\\'
