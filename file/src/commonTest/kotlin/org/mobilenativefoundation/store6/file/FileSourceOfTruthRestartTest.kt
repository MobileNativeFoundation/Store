@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSourceOfTruthRestartTest {
    @Test
    fun write_isVisibleToNewInstanceOverSameDirectory() =
        runTest {
            withFreshDirectory("store6-file-sot-restart") { directory ->
                val key = FileKitKey(StoreNamespace("users"), "a")
                val first = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                first.write(key, "persisted")

                val second = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                assertEquals("persisted", second.reader(key).first())
            }
        }

    @Test
    fun delete_isVisibleToNewInstanceOverSameDirectory() =
        runTest {
            withFreshDirectory("store6-file-sot-restart") { directory ->
                val key = FileKitKey(StoreNamespace("users"), "a")
                val first = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                first.write(key, "persisted")
                first.delete(key)

                val third = FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                assertNull(third.reader(key).first())
            }
        }
}
