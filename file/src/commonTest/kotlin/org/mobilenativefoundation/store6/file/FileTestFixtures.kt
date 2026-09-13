@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import kotlin.random.Random

internal class FileKitKey(
    override val namespace: StoreNamespace,
    private val id: String,
) : StoreKey {
    override fun canonicalId(): String = id
}

internal fun createTempDirectory(prefix: String): Path {
    val random = Random.nextLong().toULong().toString(radix = 16)
    val directory = Path(SystemTemporaryDirectory, "$prefix-$random")
    ensureDirectories(directory)
    return directory
}

internal fun cleanupDirectory(directory: Path) {
    purgeRecursively(directory)
}

internal suspend fun withFreshDirectory(
    prefix: String,
    block: suspend (Path) -> Unit,
) {
    val directory = createTempDirectory(prefix)
    try {
        block(directory)
    } finally {
        cleanupDirectory(directory)
    }
}
