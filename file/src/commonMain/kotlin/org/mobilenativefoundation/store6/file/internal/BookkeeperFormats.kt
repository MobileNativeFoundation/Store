package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Payload codecs for one bookkeeper record file and the watermarks control file.
 *
 * These functions produce and consume the payload that [Envelope.write] and [Envelope.read]
 * wrap with magics `S6FB` (record) and `S6FW` (watermarks). They do not perform IO.
 *
 * [decodeRecord] and [decodeWatermarks] return `null` when the payload is invalid. Invalidity
 * is a payload defect, not an exception: bit 1 set without bit 0, flags bits 5–7 nonzero, a
 * negative length or namespace count, a read underflow, or leftover bytes after the last
 * field. The caller treats that file as corrupt. Encoding a [PersistedRecord] or
 * [PersistedWatermarks] never fails.
 *
 * All integers are big-endian. Absent optional fields are omitted, not zeroed.
 */
internal object BookkeeperFormats {
    private const val FLAG_HAS_META: Int = 1 shl 0
    private const val FLAG_HAS_ETAG: Int = 1 shl 1
    private const val FLAG_HAS_SUCCESS_SEQUENCE: Int = 1 shl 2
    private const val FLAG_HAS_FAILURE_TIMESTAMP: Int = 1 shl 3
    private const val FLAG_HAS_STALE_SEQUENCE: Int = 1 shl 4
    private const val FLAG_RESERVED_MASK: Int = 0xE0

    /**
     * Encodes [record] as one record-file payload.
     *
     * Flag bit 0 is set when [PersistedRecord.meta] is present, bit 1 when that meta has a
     * non-null etag, bit 2 when [PersistedRecord.lastSuccessSequence] is present, bit 3 when
     * [PersistedRecord.lastFailureAtEpochMillis] is present, and bit 4 when
     * [PersistedRecord.staleSequence] is present. Bits 5–7 are zero.
     * [PersistedRecord.consecutiveFailures] is always written.
     */
    fun encodeRecord(record: PersistedRecord): ByteArray {
        var flags = 0
        val meta = record.meta
        if (meta != null) {
            flags = flags or FLAG_HAS_META
            if (meta.etag != null) {
                flags = flags or FLAG_HAS_ETAG
            }
        }
        if (record.lastSuccessSequence != null) {
            flags = flags or FLAG_HAS_SUCCESS_SEQUENCE
        }
        if (record.lastFailureAtEpochMillis != null) {
            flags = flags or FLAG_HAS_FAILURE_TIMESTAMP
        }
        if (record.staleSequence != null) {
            flags = flags or FLAG_HAS_STALE_SEQUENCE
        }

        val buffer = Buffer()
        buffer.writeByte(flags.toByte())
        if (meta != null) {
            buffer.writeLong(meta.writtenAtEpochMillis)
            val etag = meta.etag
            if (etag != null) {
                buffer.writeLengthPrefixedUtf8(etag)
            }
        }
        val lastSuccessSequence = record.lastSuccessSequence
        if (lastSuccessSequence != null) {
            buffer.writeLong(lastSuccessSequence)
        }
        val lastFailureAtEpochMillis = record.lastFailureAtEpochMillis
        if (lastFailureAtEpochMillis != null) {
            buffer.writeLong(lastFailureAtEpochMillis)
        }
        buffer.writeInt(record.consecutiveFailures)
        val staleSequence = record.staleSequence
        if (staleSequence != null) {
            buffer.writeLong(staleSequence)
        }
        return buffer.readByteArray()
    }

    /**
     * Decodes one record-file payload, or `null` when the bytes are not a valid record payload.
     */
    fun decodeRecord(payload: ByteArray): PersistedRecord? {
        val buffer = Buffer()
        buffer.write(payload)
        val flags = (buffer.readExactByte() ?: return null).toInt() and 0xFF
        if (flags and FLAG_RESERVED_MASK != 0) {
            return null
        }
        val hasMeta = flags and FLAG_HAS_META != 0
        val hasEtag = flags and FLAG_HAS_ETAG != 0
        if (hasEtag && !hasMeta) {
            return null
        }
        val hasSuccessSequence = flags and FLAG_HAS_SUCCESS_SEQUENCE != 0
        val hasFailureTimestamp = flags and FLAG_HAS_FAILURE_TIMESTAMP != 0
        val hasStaleSequence = flags and FLAG_HAS_STALE_SEQUENCE != 0

        val meta =
            if (hasMeta) {
                val writtenAtEpochMillis = buffer.readExactLong() ?: return null
                val etag =
                    if (hasEtag) {
                        buffer.readExactLengthPrefixedUtf8() ?: return null
                    } else {
                        null
                    }
                PersistedMeta(writtenAtEpochMillis, etag)
            } else {
                null
            }
        val lastSuccessSequence =
            if (hasSuccessSequence) {
                buffer.readExactLong() ?: return null
            } else {
                null
            }
        val lastFailureAtEpochMillis =
            if (hasFailureTimestamp) {
                buffer.readExactLong() ?: return null
            } else {
                null
            }
        val consecutiveFailures = buffer.readExactInt() ?: return null
        val staleSequence =
            if (hasStaleSequence) {
                buffer.readExactLong() ?: return null
            } else {
                null
            }
        if (buffer.size != 0L) {
            return null
        }
        return PersistedRecord(
            meta = meta,
            lastSuccessSequence = lastSuccessSequence,
            lastFailureAtEpochMillis = lastFailureAtEpochMillis,
            consecutiveFailures = consecutiveFailures,
            staleSequence = staleSequence,
        )
    }

    /**
     * Encodes [watermarks] as one watermarks-control-file payload.
     *
     * Layout is `globalStaleWatermark` (8), namespace count (4), then each namespace as name
     * byte length (4) + UTF-8 name bytes + watermark (8). Names are written in ascending
     * [String] order so the same map encodes to the same bytes.
     */
    fun encodeWatermarks(watermarks: PersistedWatermarks): ByteArray {
        val names = watermarks.namespaceWatermarks.keys.sorted()
        val buffer = Buffer()
        buffer.writeLong(watermarks.globalStaleWatermark)
        buffer.writeInt(names.size)
        for (name in names) {
            buffer.writeLengthPrefixedUtf8(name)
            buffer.writeLong(watermarks.namespaceWatermarks.getValue(name))
        }
        return buffer.readByteArray()
    }

    /**
     * Decodes one watermarks-control-file payload, or `null` when the bytes are not a valid
     * watermarks payload.
     */
    fun decodeWatermarks(payload: ByteArray): PersistedWatermarks? {
        val buffer = Buffer()
        buffer.write(payload)
        val globalStaleWatermark = buffer.readExactLong() ?: return null
        val count = buffer.readExactInt() ?: return null
        if (count < 0) {
            return null
        }
        val namespaceWatermarks = LinkedHashMap<String, Long>()
        repeat(count) {
            val name = buffer.readExactLengthPrefixedUtf8() ?: return null
            val watermark = buffer.readExactLong() ?: return null
            namespaceWatermarks[name] = watermark
        }
        if (buffer.size != 0L) {
            return null
        }
        return PersistedWatermarks(
            globalStaleWatermark = globalStaleWatermark,
            namespaceWatermarks = namespaceWatermarks,
        )
    }
}

/**
 * Per-key bookkeeper record fields persisted in one `S6FB` payload.
 *
 * The field set is the durable per-key record state: optional success metadata, optional last
 * success sequence, optional last failure timestamp, the consecutive-failure count, and an
 * optional per-key stale sequence. Sequences and timestamps are epoch-millis or store-local
 * sequence [Long] values. [consecutiveFailures] is always present.
 */
internal data class PersistedRecord(
    val meta: PersistedMeta?,
    val lastSuccessSequence: Long?,
    val lastFailureAtEpochMillis: Long?,
    val consecutiveFailures: Int,
    val staleSequence: Long?,
)

/**
 * Success metadata persisted when record-payload flag bit 0 is set.
 *
 * [writtenAtEpochMillis] is Unix epoch milliseconds. [etag] is the optional entity tag; a
 * non-null value sets flag bit 1. Bit 1 without bit 0 is not representable.
 */
internal data class PersistedMeta(
    val writtenAtEpochMillis: Long,
    val etag: String?,
)

/**
 * Watermarks persisted in one `S6FW` payload.
 *
 * [globalStaleWatermark] is the store-global stale sequence. [namespaceWatermarks] maps each
 * namespace name to its stale watermark. [BookkeeperFormats.encodeWatermarks] sorts by name.
 */
internal data class PersistedWatermarks(
    val globalStaleWatermark: Long,
    val namespaceWatermarks: Map<String, Long>,
)

private fun Buffer.writeLengthPrefixedUtf8(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun Buffer.readExactByte(): Byte? {
    if (size < 1L) {
        return null
    }
    return readByte()
}

private fun Buffer.readExactInt(): Int? {
    if (size < 4L) {
        return null
    }
    return readInt()
}

private fun Buffer.readExactLong(): Long? {
    if (size < 8L) {
        return null
    }
    return readLong()
}

private fun Buffer.readExactLengthPrefixedUtf8(): String? {
    val length = readExactInt() ?: return null
    if (length < 0) {
        return null
    }
    if (size < length.toLong()) {
        return null
    }
    return readByteArray(length).decodeToString()
}
