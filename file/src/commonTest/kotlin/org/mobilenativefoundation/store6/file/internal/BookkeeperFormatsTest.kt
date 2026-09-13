package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookkeeperFormatsTest {
    @Test
    fun record_roundTripsEveryValidFlagCombination() {
        for (flags in 0 until 32) {
            val hasMeta = flags and 0x01 != 0
            val hasEtag = flags and 0x02 != 0
            if (hasEtag && !hasMeta) {
                continue
            }
            val hasSuccessSequence = flags and 0x04 != 0
            val hasFailureTimestamp = flags and 0x08 != 0
            val hasStaleSequence = flags and 0x10 != 0
            val etag = if (hasEtag) "etag-$flags-café-タグ" else null
            val record =
                PersistedRecord(
                    meta =
                        if (hasMeta) {
                            PersistedMeta(
                                writtenAtEpochMillis = 1_700_000_000_000L + flags,
                                etag = etag,
                            )
                        } else {
                            null
                        },
                    lastSuccessSequence = if (hasSuccessSequence) 100L + flags else null,
                    lastFailureAtEpochMillis =
                        if (hasFailureTimestamp) {
                            2_000_000_000_000L + flags
                        } else {
                            null
                        },
                    consecutiveFailures = 1_000 + flags,
                    staleSequence = if (hasStaleSequence) 300L + flags else null,
                )
            val encoded = BookkeeperFormats.encodeRecord(record)
            var expectedSize = 1 + 4
            if (hasMeta) {
                expectedSize += 8
            }
            if (hasEtag) {
                expectedSize += 4 + etag!!.encodeToByteArray().size
            }
            if (hasSuccessSequence) {
                expectedSize += 8
            }
            if (hasFailureTimestamp) {
                expectedSize += 8
            }
            if (hasStaleSequence) {
                expectedSize += 8
            }
            assertEquals(expectedSize, encoded.size, "flags=$flags size")
            assertEquals(record, BookkeeperFormats.decodeRecord(encoded), "flags=$flags")
        }
    }

    @Test
    fun decodeRecord_bit1WithoutBit0_isInvalid() {
        for (other in 0 until 8) {
            val flags = 0x02 or (other shl 2)
            val payload =
                bytes {
                    writeByte(flags.toByte())
                    writeInt(0)
                }
            assertNull(BookkeeperFormats.decodeRecord(payload), "flags=$flags")
        }
    }

    @Test
    fun decodeRecord_nonzeroReservedBits_isInvalid() {
        for (reserved in listOf(0x20, 0x40, 0x80, 0xE0, 0x21, 0xA5)) {
            val payload =
                bytes {
                    writeByte(reserved.toByte())
                    writeInt(0)
                }
            assertNull(BookkeeperFormats.decodeRecord(payload), "flags=$reserved")
        }
    }

    @Test
    fun decodeRecord_truncatedPayload_isInvalid() {
        val record =
            PersistedRecord(
                meta = PersistedMeta(1L, "ab"),
                lastSuccessSequence = 2L,
                lastFailureAtEpochMillis = 3L,
                consecutiveFailures = 4,
                staleSequence = 5L,
            )
        val complete = BookkeeperFormats.encodeRecord(record)
        for (n in 0 until complete.size) {
            assertNull(
                BookkeeperFormats.decodeRecord(complete.copyOf(n)),
                "truncated to $n of ${complete.size}",
            )
        }
    }

    @Test
    fun decodeRecord_trailingBytes_isInvalid() {
        val emptyOptional =
            BookkeeperFormats.encodeRecord(
                PersistedRecord(
                    meta = null,
                    lastSuccessSequence = null,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = null,
                ),
            )
        assertNull(BookkeeperFormats.decodeRecord(emptyOptional + byteArrayOf(0)))

        val full =
            BookkeeperFormats.encodeRecord(
                PersistedRecord(
                    meta = PersistedMeta(1L, "x"),
                    lastSuccessSequence = 2L,
                    lastFailureAtEpochMillis = 3L,
                    consecutiveFailures = 4,
                    staleSequence = 5L,
                ),
            )
        assertNull(BookkeeperFormats.decodeRecord(full + byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun decodeRecord_negativeEtagLength_isInvalid() {
        val payload =
            bytes {
                writeByte(0x03)
                writeLong(1L)
                writeInt(-1)
            }
        assertNull(BookkeeperFormats.decodeRecord(payload))
    }

    @Test
    fun watermarks_roundTripZeroAndMultipleNamespaces() {
        val empty = PersistedWatermarks(globalStaleWatermark = 0L, namespaceWatermarks = emptyMap())
        assertEquals(empty, BookkeeperFormats.decodeWatermarks(BookkeeperFormats.encodeWatermarks(empty)))

        val many =
            PersistedWatermarks(
                globalStaleWatermark = 99L,
                namespaceWatermarks =
                    mapOf(
                        "zeta" to 7L,
                        "" to 1L,
                        "名前" to 3L,
                        "alpha" to 5L,
                    ),
            )
        assertEquals(many, BookkeeperFormats.decodeWatermarks(BookkeeperFormats.encodeWatermarks(many)))

        val reordered =
            PersistedWatermarks(
                globalStaleWatermark = 99L,
                namespaceWatermarks =
                    mapOf(
                        "alpha" to 5L,
                        "名前" to 3L,
                        "" to 1L,
                        "zeta" to 7L,
                    ),
            )
        assertContentEquals(
            BookkeeperFormats.encodeWatermarks(many),
            BookkeeperFormats.encodeWatermarks(reordered),
        )
    }

    @Test
    fun decodeWatermarks_truncatedPayload_isInvalid() {
        val complete =
            BookkeeperFormats.encodeWatermarks(
                PersistedWatermarks(
                    globalStaleWatermark = 4L,
                    namespaceWatermarks = mapOf("a" to 1L, "b" to 2L, "c" to 3L),
                ),
            )
        for (n in 0 until complete.size) {
            assertNull(
                BookkeeperFormats.decodeWatermarks(complete.copyOf(n)),
                "truncated to $n of ${complete.size}",
            )
        }
    }

    @Test
    fun decodeWatermarks_trailingBytes_isInvalid() {
        val complete =
            BookkeeperFormats.encodeWatermarks(
                PersistedWatermarks(globalStaleWatermark = 1L, namespaceWatermarks = emptyMap()),
            )
        assertNull(BookkeeperFormats.decodeWatermarks(complete + byteArrayOf(0)))

        val withNamespaces =
            BookkeeperFormats.encodeWatermarks(
                PersistedWatermarks(
                    globalStaleWatermark = 2L,
                    namespaceWatermarks = mapOf("" to 0L, "名前" to 8L, "z" to 9L),
                ),
            )
        assertNull(BookkeeperFormats.decodeWatermarks(withNamespaces + byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun decodeWatermarks_negativeCountOrNameLength_isInvalid() {
        val negativeCount =
            bytes {
                writeLong(0L)
                writeInt(-1)
            }
        assertNull(BookkeeperFormats.decodeWatermarks(negativeCount))

        val negativeNameLength =
            bytes {
                writeLong(0L)
                writeInt(1)
                writeInt(-1)
            }
        assertNull(BookkeeperFormats.decodeWatermarks(negativeNameLength))
    }

    private fun bytes(write: Buffer.() -> Unit): ByteArray {
        val buffer = Buffer()
        buffer.write()
        return buffer.readByteArray()
    }
}
