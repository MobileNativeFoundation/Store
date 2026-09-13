package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.StoreException

/** One observation from the shared source-of-truth reader pipeline. */
internal sealed interface ReaderRecord<out V : Any> {
    val readerGen: Long
    val residenceRevision: Long

    /** The reader observed [envelope] and installed it at [residenceRevision]. */
    class Row<V : Any>(
        val envelope: ValueEnvelope<V>,
        override val readerGen: Long,
        override val residenceRevision: Long,
        /** Successful-write sequence observed before this adapter event entered conflation. */
        val successfulWriteSequenceAtObservation: Long = 0L,
        /** Exact consume-once tag consumed while mapping this notification, when any. */
        val consumedAttribution: AttributionTag? = null,
        /** Exact commit whose SoT write was active when this adapter event was observed. */
        val activeWriteAttributionAtObservation: AttributionTag? = null,
        /** Monotone source-order token captured before adapter-event conflation. */
        val rawObservationSequence: Long = 0L,
    ) : ReaderRecord<V>

    /** The reader observed absence and installed it at [residenceRevision]. */
    class Absent(
        override val readerGen: Long,
        override val residenceRevision: Long,
        /** Successful-write sequence observed before this adapter event entered conflation. */
        val successfulWriteSequenceAtObservation: Long = 0L,
        /** Exact consume-once tag consumed while mapping this notification, when any. */
        val consumedAttribution: AttributionTag? = null,
        /** Exact commit whose SoT write was active when this adapter event was observed. */
        val activeWriteAttributionAtObservation: AttributionTag? = null,
        /** Monotone source-order token captured before adapter-event conflation. */
        val rawObservationSequence: Long = 0L,
    ) : ReaderRecord<Nothing>

    /** The reader failed without changing residence. */
    class Failure(
        val exception: StoreException,
        override val readerGen: Long,
        override val residenceRevision: Long,
    ) : ReaderRecord<Nothing>
}

/** Adapter-terminal event; engine mapping happens downstream so its failures are never retried. */
internal sealed interface RawReaderEvent<out V : Any> {
    class Row<V : Any>(
        val value: V?,
        /** Reader generation whose upstream adapter produced this observation. */
        val readerGen: Long,
        /** Monotone source-order token assigned before conflation. */
        val rawObservationSequence: Long,
        /** Exact consume-once tag that was current when this adapter event was observed. */
        val attributionAtObservation: AttributionTag?,
        /** Successful-write sequence current when this adapter event was observed. */
        val successfulWriteSequenceAtObservation: Long,
        /** Exact commit whose SoT write was active when this adapter event was observed. */
        val activeWriteAttributionAtObservation: AttributionTag?,
        /** True when this nonmatching event followed a matching row from that active write. */
        val followedMatchingActiveWriteRow: Boolean,
        /** True while a pre-return notification is fenced behind its committed writer-current. */
        val pendingCommitFenceAtObservation: Boolean,
    ) : RawReaderEvent<V>

    class Failure(
        val exception: StoreException,
    ) : RawReaderEvent<Nothing>
}

/**
 * Reconciles a queued reader record with live residence under the caller's state lock.
 *
 * A record is only a notification. Live residence remains authoritative when delivery is delayed.
 */
internal fun <V : Any> resolveCurrentRecord(
    record: ReaderRecord<V>,
    currentReaderGen: Long,
    currentResidence: ValueEnvelope<V>?,
    currentResidenceRevision: Long,
): ReaderRecord<V>? {
    if (record.readerGen != currentReaderGen) return null
    return when (record) {
        is ReaderRecord.Row -> {
            val live = currentResidence ?: return null
            if (live.value != record.envelope.value) return null
            ReaderRecord.Row(live, currentReaderGen, currentResidenceRevision)
        }

        is ReaderRecord.Absent -> {
            if (currentResidence != null) return null
            ReaderRecord.Absent(currentReaderGen, currentResidenceRevision)
        }

        is ReaderRecord.Failure ->
            ReaderRecord.Failure(record.exception, currentReaderGen, currentResidenceRevision)
    }
}

/** True only when a post-suspension row is the exact residence observation that was reserved. */
internal fun <V : Any> isSameResolvedRow(
    reserved: ReaderRecord.Row<V>,
    current: ReaderRecord.Row<V>,
): Boolean =
    current.readerGen == reserved.readerGen &&
        current.residenceRevision == reserved.residenceRevision &&
        current.envelope.value == reserved.envelope.value
