@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileCrossInstanceTest {
    @Test
    fun writeThroughOtherInstance_activeReaderDoesNotReemitButNewReaderSeesValue() =
        runTest {
            withFreshDirectory("store6-file-cross-instance-write") { directory ->
                val first =
                    FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val second =
                    FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = crossInstanceKey("write")

                second.reader(key).test {
                    assertNull(
                        awaitItem(),
                        "The second instance's active reader must start with the absent row",
                    )

                    first.write(key, "value")

                    expectNoEvents()
                    assertEquals(
                        "value",
                        second.reader(key).first(),
                        "A new collection must read a row written through another instance",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun deleteThroughOtherInstance_activeReaderDoesNotReemitButNewReaderSeesNull() =
        runTest {
            withFreshDirectory("store6-file-cross-instance-delete") { directory ->
                val first =
                    FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val second =
                    FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                val key = crossInstanceKey("delete")
                second.write(key, "value")
                assertEquals(
                    "value",
                    first.reader(key).first(),
                    "The first instance's new collection must read the seeded row",
                )
                assertEquals(
                    "value",
                    second.reader(key).first(),
                    "The second instance's new collection must read its seeded row",
                )

                second.reader(key).test {
                    assertEquals(
                        "value",
                        awaitItem(),
                        "The second instance's active reader must start with the seeded row",
                    )

                    first.delete(key)

                    expectNoEvents()
                    assertNull(
                        second.reader(key).first(),
                        "A new collection must observe a delete performed through another instance",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
}

private fun crossInstanceKey(id: String): FileKitKey =
    FileKitKey(
        namespace = StoreNamespace("cross-instance"),
        id = id,
    )
