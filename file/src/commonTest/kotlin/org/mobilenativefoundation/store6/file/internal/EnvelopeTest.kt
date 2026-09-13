package org.mobilenativefoundation.store6.file.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvelopeTest {
    private val magics =
        listOf(
            Envelope.MAGIC_VALUE,
            Envelope.MAGIC_RECORD,
            Envelope.MAGIC_WATERMARKS,
        )

    @Test
    fun writeThenRead_roundTripsEachMagic() {
        val payloads =
            listOf(
                ByteArray(0),
                byteArrayOf(0x00),
                "payload".encodeToByteArray(),
                byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte()),
            )
        for (magic in magics) {
            for (payload in payloads) {
                val bytes = Envelope.write(magic, payload)
                val result = Envelope.read(magic, bytes)
                val valid = assertIs<EnvelopeResult.Valid>(result, "magic=$magic")
                assertContentEquals(payload, valid.payload)
            }
        }
    }

    @Test
    fun write_emitsBigEndianHeaderLayout() {
        val payload = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        val bytes = Envelope.write(Envelope.MAGIC_VALUE, payload)
        assertEquals(Envelope.HEADER_BYTE_COUNT + payload.size, bytes.size)
        assertEquals('S'.code.toByte(), bytes[0])
        assertEquals('6'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('V'.code.toByte(), bytes[3])
        assertEquals(Envelope.FORMAT_VERSION, bytes[4])
        for (i in 5..11) {
            assertEquals(0, bytes[i], "length byte $i")
        }
        assertEquals(2, bytes[12])
        val crc = Crc32.of(payload)
        assertEquals(((crc ushr 24) and 0xFF).toByte(), bytes[13])
        assertEquals(((crc ushr 16) and 0xFF).toByte(), bytes[14])
        assertEquals(((crc ushr 8) and 0xFF).toByte(), bytes[15])
        assertEquals((crc and 0xFF).toByte(), bytes[16])
        assertContentEquals(payload, bytes.copyOfRange(17, bytes.size))
    }

    @Test
    fun read_truncatedShorterThan17_isTruncated() {
        val complete = Envelope.write(Envelope.MAGIC_VALUE, byteArrayOf(1))
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, ByteArray(0)),
            EnvelopeCorruption.Truncated,
        )
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, complete.copyOf(16)),
            EnvelopeCorruption.Truncated,
        )
    }

    @Test
    fun read_wrongMagic_isWrongMagic() {
        val bytes = Envelope.write(Envelope.MAGIC_VALUE, "x".encodeToByteArray())
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_RECORD, bytes),
            EnvelopeCorruption.WrongMagic,
        )
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_WATERMARKS, bytes),
            EnvelopeCorruption.WrongMagic,
        )
    }

    @Test
    fun read_unknownVersion_isUnknownVersion() {
        val bytes = Envelope.write(Envelope.MAGIC_VALUE, "x".encodeToByteArray())
        bytes[4] = 0x02
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, bytes),
            EnvelopeCorruption.UnknownVersion,
        )
    }

    @Test
    fun read_lengthFieldMismatch_isLengthMismatch() {
        val declaredOneButEmptyPayload = Envelope.write(Envelope.MAGIC_VALUE, ByteArray(0))
        declaredOneButEmptyPayload[12] = 1
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, declaredOneButEmptyPayload),
            EnvelopeCorruption.LengthMismatch,
        )

        val extraTrailingByte = Envelope.write(Envelope.MAGIC_VALUE, byteArrayOf(1)) + byteArrayOf(0)
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, extraTrailingByte),
            EnvelopeCorruption.LengthMismatch,
        )
    }

    @Test
    fun read_crcMismatch_isCrcMismatch() {
        val bytes = Envelope.write(Envelope.MAGIC_VALUE, byteArrayOf(1, 2, 3))
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
        assertCorrupt(
            Envelope.read(Envelope.MAGIC_VALUE, bytes),
            EnvelopeCorruption.CrcMismatch,
        )
    }

    private fun assertCorrupt(
        result: EnvelopeResult,
        reason: EnvelopeCorruption,
    ) {
        val corrupt = assertIs<EnvelopeResult.StructurallyCorrupt>(result)
        assertEquals(reason, corrupt.reason)
    }
}
