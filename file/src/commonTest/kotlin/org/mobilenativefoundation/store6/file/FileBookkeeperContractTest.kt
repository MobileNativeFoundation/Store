@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.io.files.Path
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.test.AfterTest
import kotlin.test.Test

internal class FileBookkeeperContractTest : BookkeeperContractKit() {
    private val directories = mutableListOf<Path>()

    override fun createBookkeeper(): Bookkeeper {
        val directory = createTempDirectory("store6-file-bookkeeper-kit").also { directories += it }
        return FileBookkeeper(directory)
    }

    @Test
    fun globalWatermarkCoverage() = globalWatermark_coversNeverSeenKeys()

    @Test
    fun laterSuccessCoverage() = laterSuccess_clearsOnlyEarlierStalenessForItsKey()

    @Test
    fun sharedMonotoneSequence() = marksAndSuccesses_shareOneMonotoneSequence()

    @Test
    fun forgetCoverage() = forget_removesRecordAndPreservesWatermarks()

    @Test
    fun forgetNamespaceCoverage() = forgetNamespace_removesOnlyMatchingRecordsAndPreservesWatermarks()

    @Test
    fun forgetAllCoverage() = forgetAll_removesRecordsAndPreservesWatermarks()

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
