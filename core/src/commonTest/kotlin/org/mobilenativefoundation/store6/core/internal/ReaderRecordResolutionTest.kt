package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReaderRecordResolutionTest {
    @Test
    fun sameGenerationAndRevisionRow_resolvesTheLiveEqualResidence() {
        val recorded = envelope("value")
        val live = envelope("value")

        val resolved =
            resolveCurrentRecord(
                record = ReaderRecord.Row(recorded, readerGen = 4L, residenceRevision = 9L),
                currentReaderGen = 4L,
                currentResidence = live,
                currentResidenceRevision = 9L,
            )

        val row = resolved as ReaderRecord.Row
        assertSame(live, row.envelope)
        assertEquals(9L, row.residenceRevision)
    }

    @Test
    fun equalContentReplayAtOlderRevision_resolvesTheFresherLiveEnvelope() {
        val live = envelope("value")

        val resolved =
            resolveCurrentRecord(
                record = ReaderRecord.Row(envelope("value"), 4L, 7L),
                currentReaderGen = 4L,
                currentResidence = live,
                currentResidenceRevision = 9L,
            )

        val row = resolved as ReaderRecord.Row
        assertSame(live, row.envelope)
        assertEquals(9L, row.residenceRevision)
    }

    @Test
    fun staleRowOrAbsentCannotOverwriteTheCurrentResidence() {
        val live = envelope("new")

        assertNull(
            resolveCurrentRecord(
                record = ReaderRecord.Row(envelope("old"), 2L, 3L),
                currentReaderGen = 2L,
                currentResidence = live,
                currentResidenceRevision = 4L,
            ),
        )
        assertNull(
            resolveCurrentRecord(
                record = ReaderRecord.Absent(2L, 3L),
                currentReaderGen = 2L,
                currentResidence = live,
                currentResidenceRevision = 4L,
            ),
        )
        assertNull(
            resolveCurrentRecord(
                record = ReaderRecord.Row(envelope("old"), 1L, 3L),
                currentReaderGen = 2L,
                currentResidence = null,
                currentResidenceRevision = 4L,
            ),
        )
    }

    @Test
    fun queuedAbsentBeforeWriterEcho_isRejectedOnceTheLiveRowIsInstalled() {
        val live = envelope("writer-echo")

        assertNull(
            resolveCurrentRecord(
                record = ReaderRecord.Absent(readerGen = 8L, residenceRevision = 10L),
                currentReaderGen = 8L,
                currentResidence = live,
                currentResidenceRevision = 11L,
            ),
        )
    }

    @Test
    fun postReservationRecheck_rejectsConcurrentEqualValueResidenceReplacement() {
        val reserved =
            ReaderRecord.Row(
                envelope = envelope("same-content"),
                readerGen = 5L,
                residenceRevision = 11L,
            )
        val replacement =
            ReaderRecord.Row(
                envelope = envelope("same-content"),
                readerGen = 5L,
                residenceRevision = 12L,
            )

        assertFalse(isSameResolvedRow(reserved, replacement))
    }

    @Test
    fun memoryOriginOverride_rejectsConcurrentRevisionReplacement() {
        val memory = envelope("same-content")

        assertFalse(
            canRestampMemoryOrigin(
                memoryEnvelope = memory,
                memoryRevision = 3L,
                currentEnvelope = memory,
                currentRevision = 4L,
            ),
        )
        assertFalse(
            canRestampMemoryOrigin(
                memoryEnvelope = memory,
                memoryRevision = 3L,
                currentEnvelope = envelope("same-content"),
                currentRevision = 3L,
            ),
        )
    }

    private fun envelope(value: String): ValueEnvelope<String> =
        ValueEnvelope(
            value = value,
            origin = Origin.SOT,
            meta = null,
            staleEpochAtCommit = 0L,
        )
}
