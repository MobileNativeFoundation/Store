package org.mobilenativefoundation.store6.file.internal

/**
 * Lowercase unpadded RFC 4648 base32 used for on-disk name components.
 *
 * The alphabet is `a–z` and `2–7`. Padding characters are not emitted.
 */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /**
     * Encodes the UTF-8 bytes of [value].
     *
     * The empty string encodes to `"0"`. `0` is outside the alphabet, so that sentinel cannot
     * collide with the encoding of any non-empty input.
     */
    fun encode(value: String): String {
        if (value.isEmpty()) return "0"
        return encodeBytes(value.encodeToByteArray())
    }

    /**
     * Decodes a lowercase unpadded base32 name to its UTF-8 string.
     *
     * The `"0"` sentinel decodes to the empty string. Returns `null` for an empty name, characters
     * outside the alphabet, impossible unpadded lengths, non-zero trailing bits, or invalid UTF-8.
     */
    fun decode(value: String): String? {
        if (value == "0") return ""
        if (value.isEmpty()) return null
        if (value.length % 8 in setOf(1, 3, 6)) return null

        val bytes = ByteArray(value.length * 5 / 8)
        var outputIndex = 0
        var buffer = 0
        var bitsLeft = 0
        for (character in value) {
            val alphabetIndex = ALPHABET.indexOf(character)
            if (alphabetIndex < 0) return null
            buffer = (buffer shl 5) or alphabetIndex
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                bytes[outputIndex] = ((buffer ushr bitsLeft) and 0xFF).toByte()
                outputIndex += 1
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        if (buffer != 0) return null

        val decoded = bytes.decodeToString()
        return decoded.takeIf { it.encodeToByteArray().contentEquals(bytes) }
    }

    private fun encodeBytes(bytes: ByteArray): String {
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer ushr bitsLeft) and 0x1F
                output.append(ALPHABET[index])
            }
            buffer = buffer and ((1 shl bitsLeft) - 1)
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(ALPHABET[index])
        }
        return output.toString()
    }
}
