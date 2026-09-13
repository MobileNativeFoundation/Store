@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.Base32
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Malformed UTF-16 key components must be rejected before any file or mirror change, and
 * well-formed astral-plane components must keep round-tripping to distinct names.
 *
 * `String.encodeToByteArray` replaces an unpaired surrogate with U+FFFD, so two distinct Store
 * identities can otherwise reach one on-disk name. The length rule cannot separate them because
 * both components measure the same replacement encoding.
 */
class FileMalformedKeyTest {
    @Test
    fun sourceOfTruth_malformedComponent_rejectsBeforeAnyDiskChange() =
        runTest {
            withFreshDirectory("store6-file-malformed-sot") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val before = treeSnapshot(directory)

                malformedComponents.forEach { (label, malformed) ->
                    val malformedNamespace = FileKitKey(StoreNamespace(malformed), "id")
                    val malformedId = FileKitKey(StoreNamespace("ns"), malformed)

                    assertNamesComponent(
                        "namespace",
                        assertFailsWith<IllegalArgumentException>("$label: reader must reject a malformed namespace") {
                            source.reader(malformedNamespace)
                        },
                    )
                    assertNamesComponent(
                        "canonical id",
                        assertFailsWith<IllegalArgumentException>("$label: reader must reject a malformed canonical id") {
                            source.reader(malformedId)
                        },
                    )
                    assertFailsWith<IllegalArgumentException>("$label: write must reject a malformed namespace") {
                        source.write(malformedNamespace, "value")
                    }
                    assertFailsWith<IllegalArgumentException>("$label: write must reject a malformed canonical id") {
                        source.write(malformedId, "value")
                    }
                    assertFailsWith<IllegalArgumentException>("$label: delete must reject a malformed namespace") {
                        source.delete(malformedNamespace)
                    }
                    assertFailsWith<IllegalArgumentException>("$label: delete must reject a malformed canonical id") {
                        source.delete(malformedId)
                    }
                    assertNamesComponent(
                        "namespace",
                        assertFailsWith<IllegalArgumentException>("$label: deleteNamespace must reject a malformed namespace") {
                            source.deleteNamespace(StoreNamespace(malformed))
                        },
                    )
                }

                assertEquals(before, treeSnapshot(directory), "A rejected call changed the directory tree")
            }
        }

    @Test
    fun bookkeeper_malformedComponent_rejectsBeforeAnyDiskChange() =
        runTest {
            withFreshDirectory("store6-file-malformed-bookkeeper") { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val meta = TestStoreMeta(writtenAtEpochMillis = 1L, etag = "v1")
                val before = treeSnapshot(directory)

                malformedComponents.forEach { (label, malformed) ->
                    listOf(
                        "namespace" to FileKitKey(StoreNamespace(malformed), "id"),
                        "canonical id" to FileKitKey(StoreNamespace("ns"), malformed),
                    ).forEach { (component, key) ->
                        assertNamesComponent(
                            component,
                            assertFailsWith<IllegalArgumentException>("$label: recordSuccess must reject a malformed $component") {
                                bookkeeper.recordSuccess(key, meta)
                            },
                        )
                        assertFailsWith<IllegalArgumentException>("$label: recordFailure must reject a malformed $component") {
                            bookkeeper.recordFailure(key, atEpochMillis = 5L)
                        }
                        assertFailsWith<IllegalArgumentException>("$label: status must reject a malformed $component") {
                            bookkeeper.status(key)
                        }
                        assertFailsWith<IllegalArgumentException>("$label: forget must reject a malformed $component") {
                            bookkeeper.forget(key)
                        }
                        assertFailsWith<IllegalArgumentException>("$label: markStale must reject a malformed $component") {
                            bookkeeper.markStale(key)
                        }
                    }

                    assertNamesComponent(
                        "namespace",
                        assertFailsWith<IllegalArgumentException>("$label: advanceStaleWatermark must reject a malformed namespace") {
                            bookkeeper.advanceStaleWatermark(StoreNamespace(malformed))
                        },
                    )
                    assertNamesComponent(
                        "namespace",
                        assertFailsWith<IllegalArgumentException>("$label: forgetNamespace must reject a malformed namespace") {
                            bookkeeper.forgetNamespace(StoreNamespace(malformed))
                        },
                    )
                }

                assertEquals(before, treeSnapshot(directory), "A rejected call changed the directory tree")
            }
        }

    @Test
    fun malformedPair_thatSharesAnEncodedName_neverReachesDisk() =
        runTest {
            // Each unpaired surrogate becomes U+FFFD, so the encoded name cannot tell these two
            // distinct Store identities apart. Rejection is what keeps them from aliasing.
            assertNotEquals(UNPAIRED_HIGH_THEN_LETTER, UNPAIRED_LOW_THEN_LETTER)
            assertEquals(
                Base32.encode(UNPAIRED_HIGH_THEN_LETTER),
                Base32.encode(UNPAIRED_LOW_THEN_LETTER),
            )

            withFreshDirectory("store6-file-malformed-alias") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val namespace = StoreNamespace("alias")
                val before = treeSnapshot(directory)

                assertFailsWith<IllegalArgumentException> {
                    source.write(FileKitKey(namespace, UNPAIRED_HIGH_THEN_LETTER), "first")
                }
                assertFailsWith<IllegalArgumentException> {
                    source.write(FileKitKey(namespace, UNPAIRED_LOW_THEN_LETTER), "second")
                }

                assertEquals(before, treeSnapshot(directory), "A rejected alias candidate reached disk")
            }
        }

    @Test
    fun astralComponents_roundTripAndStayDistinct() =
        runTest {
            listOf(ROCKET, SMILE, "ns-$ROCKET", "").forEach { value ->
                assertEquals(value, Base32.decode(Base32.encode(value)), "\"$value\" must round-trip")
            }
            assertNotEquals(Base32.encode(ROCKET), Base32.encode(SMILE))
            assertNotEquals(
                FileNames.keyPath(Path("root"), ROCKET, ROCKET).toString(),
                FileNames.keyPath(Path("root"), ROCKET, SMILE).toString(),
            )

            withFreshDirectory("store6-file-astral") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val namespace = StoreNamespace(ROCKET)
                val rocketKey = FileKitKey(namespace, ROCKET)
                val smileKey = FileKitKey(namespace, SMILE)

                source.write(rocketKey, "rocket")
                source.write(smileKey, "smile")

                assertEquals("rocket", source.reader(rocketKey).first())
                assertEquals("smile", source.reader(smileKey).first())

                val bookkeeper = FileBookkeeper(directory)
                bookkeeper.recordSuccess(rocketKey, TestStoreMeta(writtenAtEpochMillis = 7L, etag = "rocket"))
                assertEquals("rocket", bookkeeper.status(rocketKey)?.meta?.etag)
                assertNull(bookkeeper.status(smileKey))
            }
        }

    @Test
    fun lengthRuleAndEmptySentinel_stillHold() =
        runTest {
            assertEquals("0", Base32.encode(""))

            withFreshDirectory("store6-file-malformed-limits") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val empty = FileKitKey(StoreNamespace(""), "")
                source.write(empty, "empty")
                assertEquals("empty", source.reader(empty).first())

                val overLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES + 1)
                val atLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES)
                val namespaceFailure =
                    assertFailsWith<IllegalArgumentException> {
                        source.write(FileKitKey(StoreNamespace(overLimit), "id"), "value")
                    }
                assertNamesComponent("namespace", namespaceFailure)
                assertTrue(
                    namespaceFailure.message.orEmpty().contains("${FileNames.MAX_COMPONENT_UTF8_BYTES + 1}"),
                    "The length message must report the actual length but was \"${namespaceFailure.message}\"",
                )
                assertNamesComponent(
                    "canonical id",
                    assertFailsWith<IllegalArgumentException> {
                        source.write(FileKitKey(StoreNamespace("ns"), overLimit), "value")
                    },
                )

                val longest = FileKitKey(StoreNamespace(atLimit), atLimit)
                source.write(longest, "at-limit")
                assertEquals("at-limit", source.reader(longest).first())
            }
        }

    private fun assertNamesComponent(
        component: String,
        failure: IllegalArgumentException,
    ) {
        val message = failure.message.orEmpty()
        assertTrue(
            message.contains(component),
            "The message must name the offending component \"$component\" but was \"$message\"",
        )
    }

    private companion object {
        const val UNPAIRED_HIGH_THEN_LETTER: String = "\uD800a"
        const val UNPAIRED_LOW_THEN_LETTER: String = "\uDC00a"
        const val ROCKET: String = "🚀"
        const val SMILE: String = "🙂"

        val malformedComponents: List<Pair<String, String>> =
            listOf(
                "leading unpaired high surrogate" to UNPAIRED_HIGH_THEN_LETTER,
                "leading unpaired low surrogate" to UNPAIRED_LOW_THEN_LETTER,
                "trailing unpaired high surrogate" to "a\uD83D",
                "reversed surrogate pair" to "\uDE80\uD83D",
                "lone high surrogate" to "\uD800",
                "unpaired high surrogate after a valid pair" to "$ROCKET\uD83D",
            )
    }
}

private fun treeSnapshot(root: Path): List<String> {
    val entries = mutableListOf<String>()

    fun walk(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        entries += path.toString()
        if (metadata.isDirectory) {
            SystemFileSystem.list(path).sortedBy { it.toString() }.forEach(::walk)
        }
    }

    walk(root)
    return entries
}
