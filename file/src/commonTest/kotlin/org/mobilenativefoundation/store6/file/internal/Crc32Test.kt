package org.mobilenativefoundation.store6.file.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test
    fun ieeeCheckValue_forAscii123456789() {
        assertEquals(
            0xCBF43926.toInt(),
            Crc32.of("123456789".encodeToByteArray()),
        )
    }
}
