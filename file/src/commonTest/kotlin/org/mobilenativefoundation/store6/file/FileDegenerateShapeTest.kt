@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileDegenerateShapeTest {
    @Test
    fun emptyCanonicalId_writeReadDeleteAndNamespaceIsolation() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val emptyId = FileKitKey(StoreNamespace("users"), "")
                val sameNamespace = FileKitKey(StoreNamespace("users"), "a")
                val otherNamespace = FileKitKey(StoreNamespace("teams"), "a")
                val emptyNamespace = FileKitKey(StoreNamespace(""), "a")

                source.write(emptyId, "empty-id")
                assertEquals("empty-id", source.reader(emptyId).first())

                source.write(sameNamespace, "same-ns")
                source.delete(emptyId)
                assertNull(source.reader(emptyId).first())
                assertEquals("same-ns", source.reader(sameNamespace).first())

                source.write(emptyId, "empty-id")
                source.write(otherNamespace, "other-ns")
                source.write(emptyNamespace, "empty-ns")

                source.deleteNamespace(StoreNamespace(""))
                assertEquals("empty-id", source.reader(emptyId).first())
                assertEquals("same-ns", source.reader(sameNamespace).first())
                assertEquals("other-ns", source.reader(otherNamespace).first())
                assertNull(source.reader(emptyNamespace).first())

                source.deleteNamespace(StoreNamespace("users"))
                assertNull(source.reader(emptyId).first())
                assertNull(source.reader(sameNamespace).first())
                assertEquals("other-ns", source.reader(otherNamespace).first())
            }
        }

    @Test
    fun emptyNamespace_writeReadDeleteAndNamespaceIsolation() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val emptyNamespace = FileKitKey(StoreNamespace(""), "a")
                val otherNamespace = FileKitKey(StoreNamespace("users"), "a")

                source.write(emptyNamespace, "empty-ns")
                assertEquals("empty-ns", source.reader(emptyNamespace).first())

                source.delete(emptyNamespace)
                assertNull(source.reader(emptyNamespace).first())

                source.write(emptyNamespace, "empty-ns")
                source.write(otherNamespace, "other-ns")

                source.deleteNamespace(StoreNamespace("users"))
                assertEquals("empty-ns", source.reader(emptyNamespace).first())
                assertNull(source.reader(otherNamespace).first())

                source.write(otherNamespace, "other-ns")
                source.deleteNamespace(StoreNamespace(""))
                assertNull(source.reader(emptyNamespace).first())
                assertEquals("other-ns", source.reader(otherNamespace).first())
            }
        }

    @Test
    fun bothEmpty_writeReadDeleteAndNamespaceIsolation() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val bothEmpty = FileKitKey(StoreNamespace(""), "")
                val emptyNamespaceOtherId = FileKitKey(StoreNamespace(""), "a")
                val otherNamespace = FileKitKey(StoreNamespace("users"), "a")

                source.write(bothEmpty, "both-empty")
                assertEquals("both-empty", source.reader(bothEmpty).first())

                source.write(emptyNamespaceOtherId, "empty-ns")
                source.delete(bothEmpty)
                assertNull(source.reader(bothEmpty).first())
                assertEquals("empty-ns", source.reader(emptyNamespaceOtherId).first())

                source.write(bothEmpty, "both-empty")
                source.write(otherNamespace, "other-ns")

                source.deleteNamespace(StoreNamespace("users"))
                assertEquals("both-empty", source.reader(bothEmpty).first())
                assertEquals("empty-ns", source.reader(emptyNamespaceOtherId).first())
                assertNull(source.reader(otherNamespace).first())

                source.write(otherNamespace, "other-ns")
                source.deleteNamespace(StoreNamespace(""))
                assertNull(source.reader(bothEmpty).first())
                assertNull(source.reader(emptyNamespaceOtherId).first())
                assertEquals("other-ns", source.reader(otherNamespace).first())
            }
        }

    @Test
    fun deleteAll_succeedsTwice() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = FileKitKey(StoreNamespace("users"), "a")
                source.write(key, "value")

                source.deleteAll()
                assertNull(source.reader(key).first())

                source.deleteAll()
                assertNull(source.reader(key).first())
            }
        }

    @Test
    fun deleteNamespace_ofNeverWrittenNamespace_succeeds() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val namespace = StoreNamespace("never-written")
                val key = FileKitKey(namespace, "a")

                source.deleteNamespace(namespace)
                assertNull(source.reader(key).first())
            }
        }

    @Test
    fun write_asFirstOperationOnFreshDirectory_succeeds() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = FileKitKey(StoreNamespace("users"), "a")

                source.write(key, "first")

                assertEquals("first", source.reader(key).first())
            }
        }

    @Test
    fun delete_asFirstOperationOnFreshDirectory_succeeds() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = FileKitKey(StoreNamespace("users"), "a")

                source.delete(key)

                assertNull(source.reader(key).first())
            }
        }

    @Test
    fun deleteAll_asFirstOperationOnFreshDirectory_succeeds() =
        runTest {
            withFreshDirectory("store6-file-sot-degenerate") { directory ->
                val source = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = FileKitKey(StoreNamespace("users"), "a")

                source.deleteAll()

                assertNull(source.reader(key).first())
            }
        }
}
