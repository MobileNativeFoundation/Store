package org.mobilenativefoundation.store6.ktor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidatorTokenTest {
    @Test
    fun colonContainingEtag_roundTripsVerbatim() {
        val token =
            encodeValidatorToken(
                etagHeader = "\"a:b\"",
                lastModifiedHeader = null,
                lastModifiedFallback = true,
            )

        assertEquals("\"a:b\"", token)
        val headers = decodeValidatorToken(token!!, lastModifiedFallback = true)
        assertEquals("\"a:b\"", headers?.ifNoneMatch)
        assertNull(headers?.ifModifiedSince)
    }

    @Test
    fun weakEtag_roundTripsVerbatim() {
        val token =
            encodeValidatorToken(
                etagHeader = "W/\"x\"",
                lastModifiedHeader = null,
                lastModifiedFallback = true,
            )

        assertEquals("W/\"x\"", token)
        val headers = decodeValidatorToken(token!!, lastModifiedFallback = true)
        assertEquals("W/\"x\"", headers?.ifNoneMatch)
        assertNull(headers?.ifModifiedSince)
    }

    @Test
    fun bothResponseValidators_preferEtag() {
        assertEquals(
            "\"etag\"",
            encodeValidatorToken(
                etagHeader = "\"etag\"",
                lastModifiedHeader = "Sun, 06 Nov 1994 08:49:37 GMT",
                lastModifiedFallback = true,
            ),
        )
    }

    @Test
    fun notModifiedWithoutEtag_keepsPriorToken() {
        assertNull(selectNotModifiedValidatorToken(etagHeader = null))
    }

    @Test
    fun notModifiedWithEtag_adoptsEtagVerbatim() {
        assertEquals(
            "W/\"updated\"",
            selectNotModifiedValidatorToken(etagHeader = "W/\"updated\""),
        )
    }

    @Test
    fun disabledLastModifiedFallback_recordsNothingWithoutEtag() {
        assertNull(
            encodeValidatorToken(
                etagHeader = null,
                lastModifiedHeader = "Sun, 06 Nov 1994 08:49:37 GMT",
                lastModifiedFallback = false,
            ),
        )
    }

    @Test
    fun disabledLastModifiedFallback_doesNotDecodeLastModifiedToken() {
        assertNull(
            decodeValidatorToken(
                token = "LM:Sun, 06 Nov 1994 08:49:37 GMT",
                lastModifiedFallback = false,
            ),
        )
    }

    @Test
    fun lastModified_roundTripsExactHttpDate() {
        val httpDate = "Sun, 06 Nov 1994 08:49:37 GMT"
        val token =
            encodeValidatorToken(
                etagHeader = null,
                lastModifiedHeader = httpDate,
                lastModifiedFallback = true,
            )

        assertEquals("LM:$httpDate", token)
        val headers = decodeValidatorToken(token!!, lastModifiedFallback = true)
        assertNull(headers?.ifNoneMatch)
        assertEquals(httpDate, headers?.ifModifiedSince)
    }
}
