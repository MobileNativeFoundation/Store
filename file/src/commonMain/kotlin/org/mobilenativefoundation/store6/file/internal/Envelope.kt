package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Versioned on-disk envelope: magic, format version `0x01`, payload length, CRC32, payload.
 *
 * All integers are big-endian. The reader operates on bytes already in memory and never
 * throws for a structural defect. Callers distinguish [EnvelopeResult.StructurallyCorrupt]
 * from IO exceptions thrown by the filesystem layer.
 */
internal object Envelope {
    /** Value-file magic (`FileSourceOfTruth` rows). */
    const val MAGIC_VALUE: String = "S6FV"

    /** Bookkeeper per-key record-file magic. */
    const val MAGIC_RECORD: String = "S6FB"

    /** Bookkeeper watermarks control-file magic. */
    const val MAGIC_WATERMARKS: String = "S6FW"

    /** Sole format version this reader accepts. */
    const val FORMAT_VERSION: Byte = 0x01

    /** Byte count of magic + version + length + CRC32. A shorter buffer is truncated. */
    const val HEADER_BYTE_COUNT: Int = 17

    /**
     * Writes one envelope around [payload] using [magic].
     *
     * [magic] must be exactly four ASCII bytes (`S6FV`, `S6FB`, or `S6FW` for adapter files).
     * The CRC32 covers [payload] only.
     *
     * @throws IllegalArgumentException if [magic] is not exactly four UTF-8 bytes
     */
    fun write(
        magic: String,
        payload: ByteArray,
    ): ByteArray {
        val magicBytes = magic.encodeToByteArray()
        require(magicBytes.size == 4) {
            "envelope magic must be exactly 4 ASCII bytes, was ${magicBytes.size}"
        }
        val buffer = Buffer()
        buffer.write(magicBytes)
        buffer.writeByte(FORMAT_VERSION)
        buffer.writeLong(payload.size.toLong())
        buffer.writeInt(Crc32.of(payload))
        buffer.write(payload)
        return buffer.readByteArray()
    }

    /**
     * Reads one envelope from [bytes], requiring [expectedMagic].
     *
     * Returns [EnvelopeResult.Valid] with the payload when every structural check passes.
     * Returns [EnvelopeResult.StructurallyCorrupt] with a [EnvelopeCorruption] reason
     * otherwise. Checks run in this order, and the first failure wins:
     * shorter than 17 bytes, wrong magic, unknown version, actual remaining byte count
     * not equal to the length field, CRC32 mismatch.
     *
     * This function does not perform IO and does not throw for those defects.
     */
    fun read(
        expectedMagic: String,
        bytes: ByteArray,
    ): EnvelopeResult {
        val expectedMagicBytes = expectedMagic.encodeToByteArray()
        require(expectedMagicBytes.size == 4) {
            "envelope magic must be exactly 4 ASCII bytes, was ${expectedMagicBytes.size}"
        }
        if (bytes.size < HEADER_BYTE_COUNT) {
            return EnvelopeResult.StructurallyCorrupt(EnvelopeCorruption.Truncated)
        }
        val buffer = Buffer()
        buffer.write(bytes)
        val magicBytes = buffer.readByteArray(4)
        if (!magicBytes.contentEquals(expectedMagicBytes)) {
            return EnvelopeResult.StructurallyCorrupt(EnvelopeCorruption.WrongMagic)
        }
        val version = buffer.readByte()
        if (version != FORMAT_VERSION) {
            return EnvelopeResult.StructurallyCorrupt(EnvelopeCorruption.UnknownVersion)
        }
        val declaredLength = buffer.readLong()
        val declaredCrc = buffer.readInt()
        val payload = buffer.readByteArray()
        if (payload.size.toLong() != declaredLength) {
            return EnvelopeResult.StructurallyCorrupt(EnvelopeCorruption.LengthMismatch)
        }
        if (Crc32.of(payload) != declaredCrc) {
            return EnvelopeResult.StructurallyCorrupt(EnvelopeCorruption.CrcMismatch)
        }
        return EnvelopeResult.Valid(payload)
    }
}

/**
 * Outcome of [Envelope.read].
 *
 * [Valid] is a structurally sound envelope. [StructurallyCorrupt] is a defect on the
 * bytes themselves, not an IO failure.
 */
internal sealed class EnvelopeResult {
    /** Header and CRC32 matched. [payload] is the bytes after the 17-byte header. */
    class Valid(
        val payload: ByteArray,
    ) : EnvelopeResult()

    /**
     * The buffer is not a readable envelope of the expected magic and version.
     *
     * [reason] is the first failing corruption class. The caller decides quarantine versus
     * propagation. This is not an [kotlinx.io.IOException].
     */
    class StructurallyCorrupt(
        val reason: EnvelopeCorruption,
    ) : EnvelopeResult()
}

/**
 * Structural-corruption classes of the envelope byte layout.
 *
 * [Truncated]: fewer than 17 bytes, so the header is incomplete.
 * [WrongMagic]: the four-byte magic is not the one the caller required.
 * [UnknownVersion]: the version byte is not `0x01`.
 * [LengthMismatch]: remaining byte count after the header is not the length field.
 * [CrcMismatch]: IEEE 802.3 CRC32 of the payload is not the header CRC32.
 */
internal enum class EnvelopeCorruption {
    Truncated,
    WrongMagic,
    UnknownVersion,
    LengthMismatch,
    CrcMismatch,
}
