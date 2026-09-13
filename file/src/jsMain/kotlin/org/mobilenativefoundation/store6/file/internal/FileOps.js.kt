package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Uses the system filesystem's atomic replace operation. */
internal actual fun atomicReplace(
    source: Path,
    destination: Path,
) {
    SystemFileSystem.atomicMove(source, destination)
}
