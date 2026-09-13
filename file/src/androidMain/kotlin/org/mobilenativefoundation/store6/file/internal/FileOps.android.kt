package org.mobilenativefoundation.store6.file.internal

import android.system.ErrnoException
import android.system.Os
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Uses the system filesystem's atomic replace operation and falls back to `rename(2)` when that
 * operation is unavailable on Android API 24–25.
 */
internal actual fun atomicReplace(
    source: Path,
    destination: Path,
) {
    try {
        SystemFileSystem.atomicMove(source, destination)
    } catch (_: UnsupportedOperationException) {
        renameViaOs(source, destination)
    }
}

/**
 * Renames [source] onto [destination] with Android's `rename(2)` binding.
 *
 * [rename] is injectable for host unit tests. An [ErrnoException] is translated to
 * [IOException], preserving the original exception as its cause.
 */
internal fun renameViaOs(
    source: Path,
    destination: Path,
    rename: (String, String) -> Unit = { sourcePath, destinationPath ->
        Os.rename(sourcePath, destinationPath)
    },
) {
    try {
        rename(source.toString(), destination.toString())
    } catch (error: ErrnoException) {
        throw IOException("rename failed from $source to $destination", error)
    }
}
