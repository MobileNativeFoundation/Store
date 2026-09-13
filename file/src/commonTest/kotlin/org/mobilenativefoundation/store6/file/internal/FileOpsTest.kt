package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileOpsTest {
    @Test
    fun atomicReplace_movesAFile() =
        withFreshDirectory { directory ->
            val source = Path(directory, "source")
            val destination = Path(directory, "destination")
            write(source, "source content")

            atomicReplace(source, destination)

            assertFalse(SystemFileSystem.exists(source))
            assertEquals("source content", read(destination))
        }

    @Test
    fun atomicReplace_replacesExistingDestination() =
        withFreshDirectory { directory ->
            val source = Path(directory, "source")
            val destination = Path(directory, "destination")
            write(source, "new content")
            write(destination, "old content")

            atomicReplace(source, destination)

            assertFalse(SystemFileSystem.exists(source))
            assertEquals("new content", read(destination))
        }

    @Test
    fun uniqueFileNameGenerator_producesDistinctNames() {
        val generator = UniqueFileNameGenerator()

        val first = generator.nextName()
        val second = generator.nextName()

        assertNotEquals(first, second)
    }

    @Test
    fun ensureDirectories_isIdempotent() =
        withFreshDirectory { directory ->
            val nested = Path(directory, "one", "two")

            ensureDirectories(nested)
            ensureDirectories(nested)

            assertTrue(SystemFileSystem.metadataOrNull(nested)?.isDirectory == true)
        }

    @Test
    fun purgeRecursively_removesNestedTreeAndAbsorbsMissingPath() =
        withFreshDirectory { directory ->
            val tree = Path(directory, "tree")
            val nested = Path(tree, "one", "two")
            ensureDirectories(nested)
            write(Path(nested, "file"), "content")

            purgeRecursively(tree)
            purgeRecursively(Path(directory, "missing"))

            assertFalse(SystemFileSystem.exists(tree))
        }

    @Test
    fun sweepTemporaryDirectories_createsDirectoriesAndRemovesLeftovers() =
        withFreshDirectory { directory ->
            val temporary = Path(directory, "tmp")
            val trash = Path(directory, "trash")

            sweepTemporaryDirectories(temporary, trash)

            assertTrue(SystemFileSystem.metadataOrNull(temporary)?.isDirectory == true)
            assertTrue(SystemFileSystem.metadataOrNull(trash)?.isDirectory == true)
            ensureDirectories(Path(trash, "old", "nested"))
            write(Path(temporary, "leftover"), "content")
            write(Path(trash, "old", "nested", "leftover"), "content")

            sweepTemporaryDirectories(temporary, trash)

            assertTrue(SystemFileSystem.list(temporary).isEmpty())
            assertTrue(SystemFileSystem.list(trash).isEmpty())
        }

    private fun withFreshDirectory(block: (Path) -> Unit) {
        val random = Random.nextLong().toULong().toString(radix = 16)
        val directory = Path(SystemTemporaryDirectory, "store6-file-ops-$random")
        ensureDirectories(directory)
        try {
            block(directory)
        } finally {
            purgeRecursively(directory)
        }
    }

    private fun write(
        path: Path,
        value: String,
    ) {
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.writeString(value)
        }
    }

    private fun read(path: Path): String =
        SystemFileSystem.source(path).buffered().use { source ->
            source.readString()
        }
}
