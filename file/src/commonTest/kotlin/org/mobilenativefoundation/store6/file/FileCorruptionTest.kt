@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.Envelope
import org.mobilenativefoundation.store6.file.internal.EnvelopeCorruption
import org.mobilenativefoundation.store6.file.internal.FileNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCorruptionTest {
    @Test
    fun structuralCorruption_quarantineMovesEveryClassAndEmitsNull() =
        runTest {
            withCorruptionDirectory { directory ->
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )

                for (case in structuralCorruptionCases()) {
                    val key = corruptionKey("quarantine-${case.label}")
                    val canonicalPath = canonicalPath(directory, key)
                    val corruptPath = FileNames.corruptSibling(canonicalPath)
                    source.write(key, "seed")
                    overwrite(canonicalPath, case.bytes)

                    assertNull(
                        source.reader(key).first(),
                        "${case.label}: QUARANTINE must report structural corruption as absence",
                    )
                    assertFalse(
                        SystemFileSystem.exists(canonicalPath),
                        "${case.label}: QUARANTINE must remove the canonical file",
                    )
                    assertTrue(
                        SystemFileSystem.exists(corruptPath),
                        "${case.label}: QUARANTINE must create the .corrupt sibling",
                    )
                    assertBytesEqual(
                        case.bytes,
                        readFile(corruptPath),
                        "${case.label}: quarantine must preserve the planted corrupt bytes",
                    )
                }
            }
        }

    @Test
    fun structuralCorruption_propagateThrowsAndLeavesEveryClassUntouched() =
        runTest {
            withCorruptionDirectory { directory ->
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.PROPAGATE,
                        ioContext = coroutineContext,
                    )

                for (case in structuralCorruptionCases()) {
                    val key = corruptionKey("propagate-${case.label}")
                    val canonicalPath = canonicalPath(directory, key)
                    val corruptPath = FileNames.corruptSibling(canonicalPath)
                    source.write(key, "seed")
                    overwrite(canonicalPath, case.bytes)

                    val failure =
                        assertFailsWith<IOException>(
                            "${case.label}: PROPAGATE must throw IOException for structural corruption",
                        ) {
                            source.reader(key).first()
                        }

                    assertTrue(
                        failure.message?.contains(case.reason.name) == true,
                        "${case.label}: IOException must name corruption class ${case.reason.name}",
                    )
                    assertTrue(
                        SystemFileSystem.exists(canonicalPath),
                        "${case.label}: PROPAGATE must retain the canonical file",
                    )
                    assertBytesEqual(
                        case.bytes,
                        readFile(canonicalPath),
                        "${case.label}: PROPAGATE must leave the planted bytes untouched",
                    )
                    assertFalse(
                        SystemFileSystem.exists(corruptPath),
                        "${case.label}: PROPAGATE must not create a .corrupt sibling",
                    )
                }
            }
        }

    @Test
    fun decodeFailure_quarantineMovesStableSnapshotAndEmitsNull() =
        runTest {
            withCorruptionDirectory { directory ->
                val decodeFailure = RuntimeException("poison payload")
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = ThrowingStringCodec("poison", decodeFailure),
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )
                val key = corruptionKey("decode-quarantine")
                val canonicalPath = canonicalPath(directory, key)
                val corruptPath = FileNames.corruptSibling(canonicalPath)
                source.write(key, "poison")
                val plantedBytes = readFile(canonicalPath)

                assertNull(
                    source.reader(key).first(),
                    "QUARANTINE must report a stable decode-failing snapshot as absence",
                )
                assertFalse(
                    SystemFileSystem.exists(canonicalPath),
                    "QUARANTINE must remove the stable decode-failing canonical file",
                )
                assertTrue(
                    SystemFileSystem.exists(corruptPath),
                    "QUARANTINE must create a .corrupt sibling for a stable decode failure",
                )
                assertBytesEqual(
                    plantedBytes,
                    readFile(corruptPath),
                    "Decode-failure quarantine must preserve the failed snapshot",
                )
            }
        }

    @Test
    fun decodeFailure_propagateRethrowsOriginalAndLeavesFileUntouched() =
        runTest {
            withCorruptionDirectory { directory ->
                val decodeFailure = RuntimeException("poison payload")
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = ThrowingStringCodec("poison", decodeFailure),
                        corruptionPolicy = FileCorruptionPolicy.PROPAGATE,
                        ioContext = coroutineContext,
                    )
                val key = corruptionKey("decode-propagate")
                val canonicalPath = canonicalPath(directory, key)
                val corruptPath = FileNames.corruptSibling(canonicalPath)
                source.write(key, "poison")
                val plantedBytes = readFile(canonicalPath)

                val thrown =
                    assertFailsWith<RuntimeException>(
                        "PROPAGATE must throw the codec's RuntimeException",
                    ) {
                        source.reader(key).first()
                    }

                assertTrue(
                    thrown === decodeFailure,
                    "PROPAGATE must rethrow the original codec exception instance",
                )
                assertTrue(
                    SystemFileSystem.exists(canonicalPath),
                    "PROPAGATE must retain a decode-failing canonical file",
                )
                assertBytesEqual(
                    plantedBytes,
                    readFile(canonicalPath),
                    "PROPAGATE must leave decode-failing bytes untouched",
                )
                assertFalse(
                    SystemFileSystem.exists(corruptPath),
                    "PROPAGATE must not create a .corrupt sibling after decode failure",
                )
            }
        }

    @Test
    fun changedSnapshotAfterDecodeFailure_retriesSameCollectionWithoutQuarantine() =
        runTest {
            withCorruptionDirectory { directory ->
                val key = corruptionKey("retry")
                val canonicalPath = canonicalPath(directory, key)
                val corruptPath = FileNames.corruptSibling(canonicalPath)
                val codec = ReplacingDecodeCodec(canonicalPath)
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = codec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )
                source.write(key, "poison")
                val freshBytes = Envelope.write(Envelope.MAGIC_VALUE, "fresh".encodeToByteArray())

                assertEquals(
                    "fresh",
                    source.reader(key).first(),
                    "The same collection must retry and emit the replacement snapshot",
                )
                assertEquals(
                    2,
                    codec.decodeCalls,
                    "Changed-snapshot recovery must decode the failed and replacement snapshots",
                )
                assertFalse(
                    SystemFileSystem.exists(corruptPath),
                    "A changed snapshot must not be quarantined",
                )
                assertTrue(
                    SystemFileSystem.exists(canonicalPath),
                    "Changed-snapshot recovery must retain the replacement canonical file",
                )
                assertBytesEqual(
                    freshBytes,
                    readFile(canonicalPath),
                    "Changed-snapshot recovery must retain the fresh envelope",
                )
            }
        }

    @Test
    fun snapshotsAreEqual_distinguishesEqualContentFromDifferentContentOrLength() {
        assertTrue(
            snapshotsAreEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)),
            "Equal snapshot contents must choose the quarantine branch",
        )
        assertFalse(
            snapshotsAreEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)),
            "Different snapshot contents must choose the retry branch",
        )
        assertFalse(
            snapshotsAreEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)),
            "Different snapshot lengths must choose the retry branch",
        )
    }

    @Test
    fun quarantinedFile_isNotReadAgainAndNextWriteRemovesSibling() =
        runTest {
            withCorruptionDirectory { directory ->
                val key = corruptionKey("quarantine-lifecycle")
                val canonicalPath = canonicalPath(directory, key)
                val corruptPath = FileNames.corruptSibling(canonicalPath)
                val first =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )
                first.write(key, "seed")
                val corruptBytes = ByteArray(Envelope.HEADER_BYTE_COUNT - 1) { 0x5A }
                overwrite(canonicalPath, corruptBytes)
                assertNull(
                    first.reader(key).first(),
                    "Initial corrupt read must quarantine the canonical file",
                )
                val quarantinedBytes = readFile(corruptPath)

                val reopened =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )
                assertNull(
                    reopened.reader(key).first(),
                    "A fresh reader must treat a quarantined-only key as absent",
                )
                assertTrue(
                    SystemFileSystem.exists(corruptPath),
                    "A fresh reader must leave the .corrupt sibling in place",
                )
                assertBytesEqual(
                    quarantinedBytes,
                    readFile(corruptPath),
                    "A fresh reader must not modify quarantined bytes",
                )

                reopened.write(key, "fresh")

                assertFalse(
                    SystemFileSystem.exists(corruptPath),
                    "A successful write must clean up the key's .corrupt sibling",
                )
                assertTrue(
                    SystemFileSystem.exists(canonicalPath),
                    "A successful write must restore the canonical file",
                )
                assertEquals(
                    "fresh",
                    reopened.reader(key).first(),
                    "The replacement write must be readable",
                )
            }
        }

    @Test
    fun decodeCancellation_propagatesWithoutMutatingFilesystem() =
        runTest {
            withCorruptionDirectory { directory ->
                val cancellation = CancellationException("decode cancelled")
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = ThrowingStringCodec("poison", cancellation),
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                    )
                val key = corruptionKey("decode-cancellation")
                val canonicalPath = canonicalPath(directory, key)
                val corruptPath = FileNames.corruptSibling(canonicalPath)
                source.write(key, "poison")
                val plantedBytes = readFile(canonicalPath)

                val thrown =
                    assertFailsWith<CancellationException>(
                        "Decode CancellationException must propagate from collection",
                    ) {
                        source.reader(key).first()
                    }

                assertTrue(
                    thrown === cancellation,
                    "Collection must propagate the original CancellationException instance",
                )
                assertTrue(
                    SystemFileSystem.exists(canonicalPath),
                    "Decode cancellation must retain the canonical file",
                )
                assertBytesEqual(
                    plantedBytes,
                    readFile(canonicalPath),
                    "Decode cancellation must leave canonical bytes untouched",
                )
                assertFalse(
                    SystemFileSystem.exists(corruptPath),
                    "Decode cancellation must not create a .corrupt sibling",
                )
            }
        }
}

private data class StructuralCorruptionCase(
    val label: String,
    val reason: EnvelopeCorruption,
    val bytes: ByteArray,
)

private class ThrowingStringCodec(
    private val rejectedValue: String,
    private val failure: Throwable,
) : FileCodec<String> {
    override fun encode(
        value: String,
        sink: Sink,
    ) {
        sink.writeString(value)
    }

    override fun decode(source: Source): String {
        val value = source.readString()
        if (value == rejectedValue) throw failure
        return value
    }
}

private class ReplacingDecodeCodec(
    private val canonicalPath: Path,
) : FileCodec<String> {
    var decodeCalls: Int = 0
        private set

    override fun encode(
        value: String,
        sink: Sink,
    ) {
        sink.writeString(value)
    }

    override fun decode(source: Source): String {
        decodeCalls += 1
        val value = source.readString()
        if (value == "poison") {
            overwrite(
                canonicalPath,
                Envelope.write(Envelope.MAGIC_VALUE, "fresh".encodeToByteArray()),
            )
            throw RuntimeException("poison snapshot replaced")
        }
        return value
    }
}

private fun structuralCorruptionCases(): List<StructuralCorruptionCase> {
    val unknownVersion =
        Envelope.write(Envelope.MAGIC_VALUE, "version".encodeToByteArray()).also { bytes ->
            bytes[4] = 0x02
        }
    val lengthMismatch =
        Envelope.write(Envelope.MAGIC_VALUE, "length".encodeToByteArray()).let { bytes ->
            bytes.copyOf(bytes.size - 1)
        }
    val crcMismatch =
        Envelope.write(Envelope.MAGIC_VALUE, "crc".encodeToByteArray()).also { bytes ->
            val payloadOffset = Envelope.HEADER_BYTE_COUNT
            bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0x01).toByte()
        }
    return listOf(
        StructuralCorruptionCase(
            label = "truncated",
            reason = EnvelopeCorruption.Truncated,
            bytes = ByteArray(Envelope.HEADER_BYTE_COUNT - 1),
        ),
        StructuralCorruptionCase(
            label = "wrong-magic",
            reason = EnvelopeCorruption.WrongMagic,
            bytes = Envelope.write("NOPE", "magic".encodeToByteArray()),
        ),
        StructuralCorruptionCase(
            label = "unknown-version",
            reason = EnvelopeCorruption.UnknownVersion,
            bytes = unknownVersion,
        ),
        StructuralCorruptionCase(
            label = "length-mismatch",
            reason = EnvelopeCorruption.LengthMismatch,
            bytes = lengthMismatch,
        ),
        StructuralCorruptionCase(
            label = "crc-mismatch",
            reason = EnvelopeCorruption.CrcMismatch,
            bytes = crcMismatch,
        ),
    )
}

private fun corruptionKey(id: String): FileKitKey =
    FileKitKey(
        namespace = StoreNamespace("corruption"),
        id = id,
    )

private fun canonicalPath(
    directory: Path,
    key: FileKitKey,
): Path =
    FileNames.keyPath(
        Path(directory, "values"),
        key.namespace.value,
        key.canonicalId(),
    )

private fun overwrite(
    path: Path,
    bytes: ByteArray,
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(bytes)
    }
}

private fun readFile(path: Path): ByteArray =
    SystemFileSystem.source(path).buffered().use { source ->
        source.readByteArray()
    }

private fun assertBytesEqual(
    expected: ByteArray,
    actual: ByteArray,
    message: String,
) {
    assertTrue(expected.contentEquals(actual), message)
}

private suspend fun withCorruptionDirectory(block: suspend (Path) -> Unit) {
    val directory = createTempDirectory("store6-file-corruption")
    try {
        block(directory)
    } finally {
        cleanupDirectory(directory)
    }
}
