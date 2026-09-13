@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.io.files.Path
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import kotlin.test.AfterTest
import kotlin.test.Test

internal class FileSourceOfTruthContractTest :
    SourceOfTruthContractKit<FileKitKey, String>() {
    private val directories = mutableListOf<Path>()

    override fun createSourceOfTruth(): SourceOfTruth<FileKitKey, String> {
        val directory = createTempDirectory("store6-file-sot-kit").also { directories += it }
        return FileSourceOfTruth(
            directory = directory,
            codec = Utf8StringFileCodec,
        )
    }

    override val keyA: FileKitKey = FileKitKey(StoreNamespace("users"), "a")
    override val keyB: FileKitKey = FileKitKey(StoreNamespace("users"), "b")
    override val keyOtherNamespace: FileKitKey = FileKitKey(StoreNamespace("teams"), "a")

    override fun value(index: Int): String = "value-$index"

    @Test
    fun cancelledCallerOutcome() = mutations_cancelledCaller_obeyOutcome()

    @AfterTest
    fun cleanupDirectories() {
        var firstFailure: Throwable? = null
        directories.forEach { directory ->
            try {
                cleanupDirectory(directory)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        directories.clear()
        firstFailure?.let { throw it }
    }
}
