@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.devtools.compose

import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.devtools.DevtoolsSnapshot
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsEvent
import kotlin.time.Duration

internal class KeyRowUi(
    val namespace: String,
    val key: String,
    val stateLabel: String,
    val originLabel: String,
    val ageLabel: String,
    val isSelected: Boolean,
)

internal class EventRowUi(
    val seq: Long,
    val atLabel: String,
    val kindLabel: String,
    val keyLabel: String,
    val detailLabel: String,
)

internal class InspectorUiState(
    val tab: InspectorTab,
    val keyRows: List<KeyRowUi>,
    val timelineHeader: String?,
    val timelineRows: List<EventRowUi>,
    val eventRows: List<EventRowUi>,
    val dropNotice: String?,
    val emptyHint: String?,
)

internal fun deriveInspectorUiState(
    snapshot: DevtoolsSnapshot,
    now: Duration,
    state: InspectorState,
): InspectorUiState {
    val keyRows = snapshot.keys.map { entry ->
        KeyRowUi(
            namespace = entry.namespace,
            key = entry.key,
            stateLabel = entry.state.name,
            originLabel = entry.lastOrigin?.name ?: "—",
            ageLabel = ageLabel(entry.lastFetchSucceededAt, now),
            isSelected = state.selected?.let {
                it.namespace == entry.namespace && it.key == entry.key
            } == true,
        )
    }
    val selectedEntry = state.selected?.let { selected ->
        snapshot.keys.firstOrNull {
            it.namespace == selected.namespace && it.key == selected.key
        }
    }
    val timelineRows = state.selected?.let { selected ->
        snapshot.events
            .filter { it.namespace == selected.namespace && it.key == selected.key }
            .map(::eventRow)
    }.orEmpty()
    return InspectorUiState(
        tab = state.tab,
        keyRows = keyRows,
        timelineHeader = selectedEntry?.let {
            "${it.namespace} / ${it.key} — ${it.state.name}, ${ageLabel(it.lastFetchSucceededAt, now)}"
        },
        timelineRows = timelineRows,
        eventRows = snapshot.events.asReversed().map(::eventRow),
        dropNotice = snapshot.droppedEvents.takeIf { it > 0 }
            ?.let { "$it older events dropped" },
        emptyHint = if (snapshot.lastSeq == 0L) {
            "No events yet — install with telemetry(monitor) in your store {} builder."
        } else {
            null
        },
    )
}

private fun eventRow(event: StoreDevtoolsEvent): EventRowUi {
    val (kind, detail) = when (event) {
        is StoreDevtoolsEvent.FetchStarted -> "fetch_started" to ""
        is StoreDevtoolsEvent.FetchSucceeded ->
            "fetch_succeeded" to "fetch_ms=${event.fetchDuration.inWholeMilliseconds}"
        is StoreDevtoolsEvent.FetchFailed ->
            "fetch_failed" to
                "fetch_ms=${event.fetchDuration.inWholeMilliseconds} error=${errorV0Name(event.error)}"
        is StoreDevtoolsEvent.Served -> "serve" to "origin=${event.origin.name}"
        is StoreDevtoolsEvent.Invalidated -> "invalidate" to ""
        is StoreDevtoolsEvent.Cleared -> "clear" to ""
    }
    return EventRowUi(
        seq = event.seq,
        atLabel = elapsedLabel(event.at),
        kindLabel = kind,
        keyLabel = "${event.namespace}/${event.key}",
        detailLabel = detail,
    )
}

private fun errorV0Name(error: StoreError): String =
    when (error) {
        is StoreError.Fetch -> "Fetch"
        is StoreError.Persistence -> "Persistence"
        is StoreError.Conversion -> "Conversion"
        is StoreError.FreshnessUnsatisfiable -> "FreshnessUnsatisfiable"
        is StoreError.Conflict -> "Conflict"
        is StoreError.Missing -> "Missing"
    }

internal fun ageLabel(
    anchor: Duration?,
    now: Duration,
): String = anchor
    ?.let { (now - it).coerceAtLeast(Duration.ZERO) }
    ?.let { "age ${elapsedLabel(it)}" }
    ?: "age unknown"

internal fun elapsedLabel(elapsed: Duration): String {
    val tenths = elapsed.inWholeMilliseconds / 100
    return "${tenths / 10}.${tenths % 10}s"
}
