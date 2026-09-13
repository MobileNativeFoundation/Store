package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun requireValidComponents_accepts159Utf8BytesOnEachPart() {
        val atLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES)
        FileNames.requireValidComponents(namespace = atLimit, canonicalId = "id")
        FileNames.requireValidComponents(namespace = "ns", canonicalId = atLimit)
        FileNames.requireValidComponents(namespace = atLimit, canonicalId = atLimit)
        FileNames.requireValidComponents(namespace = "", canonicalId = "")
    }

    @Test
    fun requireValidComponents_rejects160Utf8BytesOnNamespace() {
        val overLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES + 1)
        val error =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireValidComponents(namespace = overLimit, canonicalId = "id")
            }
        assertExceptionNamesPartLimitAndActual(
            message = error.message,
            part = "namespace",
            actualLength = overLimit.encodeToByteArray().size,
        )
    }

    @Test
    fun requireValidComponents_rejects160Utf8BytesOnCanonicalId() {
        val overLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES + 1)
        val error =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireValidComponents(namespace = "ns", canonicalId = overLimit)
            }
        assertExceptionNamesPartLimitAndActual(
            message = error.message,
            part = "canonical id",
            actualLength = overLimit.encodeToByteArray().size,
        )
    }

    @Test
    fun requireValidComponents_measuresUtf8BytesNotCharacters() {
        val twoByteChar = "é"
        assertEquals(2, twoByteChar.encodeToByteArray().size)
        val atLimit = twoByteChar.repeat(79) + "a"
        assertEquals(FileNames.MAX_COMPONENT_UTF8_BYTES, atLimit.encodeToByteArray().size)
        FileNames.requireValidComponents(namespace = atLimit, canonicalId = atLimit)

        val overLimit = twoByteChar.repeat(80)
        assertEquals(160, overLimit.encodeToByteArray().size)
        val namespaceError =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireValidComponents(namespace = overLimit, canonicalId = "id")
            }
        assertExceptionNamesPartLimitAndActual(
            message = namespaceError.message,
            part = "namespace",
            actualLength = 160,
        )
        val canonicalError =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireValidComponents(namespace = "ns", canonicalId = overLimit)
            }
        assertExceptionNamesPartLimitAndActual(
            message = canonicalError.message,
            part = "canonical id",
            actualLength = 160,
        )
    }

    @Test
    fun requireValidComponents_acceptsWellFormedSurrogatePairs() {
        listOf("🚀", "🙂🚀", "orders-🚀-42", "é", "", "plain").forEach { wellFormed ->
            FileNames.requireValidComponents(namespace = wellFormed, canonicalId = wellFormed)
        }
    }

    @Test
    fun requireValidComponents_rejectsUnpairedSurrogatesNamingPartAndIndex() {
        val malformed =
            listOf(
                "\uD800a" to 0,
                "\uDC00a" to 0,
                "a\uD83D" to 1,
                "\uDE80\uD83D" to 0,
                "\uD800" to 0,
                "🚀\uD83D" to 2,
                "ok🚀\uD800x" to 4,
            )
        malformed.forEach { (value, index) ->
            assertNamesMalformedPartAndIndex(
                message =
                    assertFailsWith<IllegalArgumentException> {
                        FileNames.requireValidComponents(namespace = value, canonicalId = "id")
                    }.message,
                part = "namespace",
                index = index,
            )
            assertNamesMalformedPartAndIndex(
                message =
                    assertFailsWith<IllegalArgumentException> {
                        FileNames.requireValidComponents(namespace = "ns", canonicalId = value)
                    }.message,
                part = "canonical id",
                index = index,
            )
        }
    }

    @Test
    fun requireValidComponents_reportsMalformedBeforeLength() {
        // The UTF-8 length of a malformed component measures its U+FFFD replacement encoding,
        // so well-formedness must be decided first.
        val malformedAndOverLimit = "\uD800" + "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES)
        assertTrue(malformedAndOverLimit.encodeToByteArray().size > FileNames.MAX_COMPONENT_UTF8_BYTES)
        assertNamesMalformedPartAndIndex(
            message =
                assertFailsWith<IllegalArgumentException> {
                    FileNames.requireValidComponents(namespace = malformedAndOverLimit, canonicalId = "id")
                }.message,
            part = "namespace",
            index = 0,
        )
    }

    @Test
    fun requireValidComponents_reportsNamespaceBeforeCanonicalId() {
        assertNamesMalformedPartAndIndex(
            message =
                assertFailsWith<IllegalArgumentException> {
                    FileNames.requireValidComponents(namespace = "a\uD800", canonicalId = "\uDC00b")
                }.message,
            part = "namespace",
            index = 1,
        )
    }

    @Test
    fun keyPath_encodesUnderCallerSubtree() {
        val root = Path("values")
        assertEquals(
            Path(root, Base32.encode("orders"), Base32.encode("42")),
            FileNames.keyPath(root, namespace = "orders", canonicalId = "42"),
        )
        assertEquals(
            Path(root, "0", "0"),
            FileNames.keyPath(root, namespace = "", canonicalId = ""),
        )
    }

    @Test
    fun namespaceDirectory_encodesUnderCallerSubtree() {
        val root = Path("records")
        assertEquals(
            Path(root, Base32.encode("orders")),
            FileNames.namespaceDirectory(root, namespace = "orders"),
        )
        assertEquals(Path(root, "0"), FileNames.namespaceDirectory(root, namespace = ""))
    }

    @Test
    fun corruptSibling_appendsCorruptSuffixToFileName() {
        val path = Path("values", "aaa", "bbb")
        assertEquals(Path("values", "aaa", "bbb.corrupt"), FileNames.corruptSibling(path))
        assertEquals(Path("bbb.corrupt"), FileNames.corruptSibling(Path("bbb")))
    }

    private fun assertNamesMalformedPartAndIndex(
        message: String?,
        part: String,
        index: Int,
    ) {
        val text = requireNotNull(message)
        assertTrue(text.contains(part), "message must name $part: $text")
        assertTrue(text.contains("malformed"), "message must name the malformed sequence: $text")
        assertTrue(text.contains("index $index"), "message must name index $index: $text")
    }

    private fun assertExceptionNamesPartLimitAndActual(
        message: String?,
        part: String,
        actualLength: Int,
    ) {
        val text = requireNotNull(message)
        assertTrue(text.contains(part), "message must name $part: $text")
        assertTrue(
            text.contains(FileNames.MAX_COMPONENT_UTF8_BYTES.toString()),
            "message must name limit ${FileNames.MAX_COMPONENT_UTF8_BYTES}: $text",
        )
        assertTrue(
            text.contains(actualLength.toString()),
            "message must name actual length $actualLength: $text",
        )
    }
}
