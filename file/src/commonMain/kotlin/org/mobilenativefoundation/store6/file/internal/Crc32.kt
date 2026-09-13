package org.mobilenativefoundation.store6.file.internal

/**
 * Table-driven CRC32 using the IEEE 802.3 polynomial.
 *
 * The table is the standard reflected form of polynomial `0xEDB88320`. The register is
 * initialized to `0xFFFFFFFF` and xor-ed with `0xFFFFFFFF` after the last byte. The check
 * value for the ASCII bytes of `"123456789"` is `0xCBF43926`.
 */
internal object Crc32 {
    private val polynomial: Int = 0xEDB88320.toInt()
    private val allBits: Int = 0xFFFFFFFF.toInt()

    private val table: IntArray =
        IntArray(256) { index ->
            var crc = index
            repeat(8) {
                crc =
                    if ((crc and 1) != 0) {
                        (crc ushr 1) xor polynomial
                    } else {
                        crc ushr 1
                    }
            }
            crc
        }

    /**
     * Returns the IEEE 802.3 CRC32 of [bytes] as a 32-bit two's-complement [Int].
     *
     * Equality of two results compares the CRC bit pattern. The value is written to the
     * envelope header as four big-endian bytes of that same bit pattern.
     */
    fun of(bytes: ByteArray): Int {
        var crc = allBits
        for (byte in bytes) {
            val index = (crc xor (byte.toInt() and 0xFF)) and 0xFF
            crc = table[index] xor (crc ushr 8)
        }
        return crc xor allBits
    }
}
