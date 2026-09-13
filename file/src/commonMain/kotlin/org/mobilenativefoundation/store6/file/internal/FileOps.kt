package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

/**
 * Atomically renames [source] onto [destination], replacing an existing destination.
 *
 * This is the only rename primitive used by the adapter. A failure leaves the caller responsible
 * for recovery and is reported by throwing.
 */
internal expect fun atomicReplace(
    source: Path,
    destination: Path,
)

/**
 * Creates [path] and any missing parents, or leaves an existing directory unchanged.
 *
 * Filesystem failures propagate to the caller.
 */
internal fun ensureDirectories(path: Path) {
    SystemFileSystem.createDirectories(path, mustCreate = false)
}

/**
 * Generates names for temporary and trash entries.
 *
 * Each name combines a monotonic counter with a random component and does not contain a key or
 * namespace. Use one generator per adapter instance. The caller must serialize [nextName] calls.
 */
internal class UniqueFileNameGenerator {
    private var counter: ULong = 0u

    /**
     * Returns the next filesystem-safe token.
     *
     * The caller must serialize calls. The token is not derived from stored key material.
     */
    internal fun nextName(): String {
        val current = counter
        counter += 1u
        val random = Random.nextLong().toULong().toString(radix = 36)
        return "$current-$random"
    }
}

/**
 * Deletes [path] and its descendants when possible.
 *
 * Every metadata, listing, and deletion failure is absorbed. A failed deletion may leave any
 * portion of the tree in place for a later sweep.
 */
internal fun purgeRecursively(path: Path) {
    val metadata =
        try {
            SystemFileSystem.metadataOrNull(path)
        } catch (_: Throwable) {
            null
        }
    if (metadata?.isDirectory == true) {
        val children =
            try {
                SystemFileSystem.list(path)
            } catch (_: Throwable) {
                emptyList()
            }
        for (child in children) {
            purgeRecursively(child)
        }
    }
    try {
        SystemFileSystem.delete(path, mustExist = false)
    } catch (_: Throwable) {
        // Cleanup is best-effort. A later first-operation sweep retries leftovers.
    }
}

/**
 * Ensures [temporaryDirectory] and [trashDirectory] exist, then purges their current entries.
 *
 * Directory-creation failures propagate so the operation requiring these directories fails.
 * Listing and purge failures are absorbed, and leftovers remain eligible for a later sweep.
 */
internal fun sweepTemporaryDirectories(
    temporaryDirectory: Path,
    trashDirectory: Path,
) {
    ensureDirectories(temporaryDirectory)
    ensureDirectories(trashDirectory)
    for (directory in listOf(temporaryDirectory, trashDirectory)) {
        val entries =
            try {
                SystemFileSystem.list(directory)
            } catch (_: Throwable) {
                emptyList()
            }
        for (entry in entries) {
            purgeRecursively(entry)
        }
    }
}
