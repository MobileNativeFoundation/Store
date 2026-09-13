package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.file.internal.Envelope
import org.mobilenativefoundation.store6.file.internal.EnvelopeResult
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.UniqueFileNameGenerator
import org.mobilenativefoundation.store6.file.internal.atomicReplace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import org.mobilenativefoundation.store6.file.internal.sweepTemporaryDirectories
import kotlin.coroutines.CoroutineContext

/**
 * Filesystem-backed source of truth for one file per canonical key.
 *
 * Every [reader] first emits the current row or `null` when its path is absent and never completes
 * normally. Active readers observe matching mutations made through this instance, including
 * equal-value rewrites. A normally returning mutation has applied its row or absence and
 * published the matching notification. A mutation that throws has not been applied.
 *
 * Reader signals are instance-scoped. Changes made through another instance are reflected in the
 * first emission of a new collection but are not announced to an already-active collection.
 * Only one live `FileSourceOfTruth` may use a directory. A [FileBookkeeper] may use the same
 * directory because the two classes own disjoint subtrees.
 *
 * This class is not a `TransactionalSourceOfTruth`, so integrations that engage only over that
 * interface treat it as non-transactional.
 *
 * `namespace.value` and `canonicalId()` each must be well-formed UTF-16 and at most 159 UTF-8
 * bytes. A component holding an unpaired surrogate, or a longer component, throws
 * [IllegalArgumentException] and applies nothing. Empty strings are valid.
 *
 * @param K the key type used to locate a row
 * @param V the non-null row type
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FileSourceOfTruth<K : StoreKey, V : Any> internal constructor(
    private val directory: Path,
    private val codec: FileCodec<V>,
    private val corruptionPolicy: FileCorruptionPolicy,
    ioContext: CoroutineContext,
    private val beforeAdmissionTestGate: suspend () -> Unit = {},
    private val afterAdmissionTestGate: suspend () -> Unit = {},
    private val beforeDiskWriteTestGate: () -> Unit = {},
) : SourceOfTruth<K, V> {
    public constructor(
        directory: Path,
        codec: FileCodec<V>,
        corruptionPolicy: FileCorruptionPolicy = FileCorruptionPolicy.QUARANTINE,
        ioContext: CoroutineContext = Dispatchers.Default,
    ) : this(
        directory = directory,
        codec = codec,
        corruptionPolicy = corruptionPolicy,
        ioContext = ioContext,
        beforeAdmissionTestGate = {},
        afterAdmissionTestGate = {},
        beforeDiskWriteTestGate = {},
    )

    private val ioContext: CoroutineContext = ioContext.minusKey(Job)
    private val valuesDirectory: Path = Path(directory, "values")
    private val temporaryDirectory: Path = Path(directory, "values-tmp")
    private val trashDirectory: Path = Path(directory, "values-trash")
    private val mutex: Mutex = Mutex()
    private val temporaryNames: UniqueFileNameGenerator = UniqueFileNameGenerator()
    private val activeReaders: MutableStateFlow<Map<KeyIdentity, ReaderEntry>> =
        MutableStateFlow(emptyMap())
    private var temporaryDirectoriesSwept: Boolean = false

    public override fun reader(key: K): Flow<V?> {
        val identity = KeyIdentity(key)
        requireValid(identity)
        return flow {
            val signal = acquireReader(identity)
            try {
                emitAll(signal.map { readCurrentRow(identity) })
            } finally {
                releaseReader(identity, signal)
            }
        }
    }

    public override suspend fun write(
        key: K,
        value: V,
    ) {
        val identity = KeyIdentity(key)
        requireValid(identity)
        val payloadBuffer = Buffer()
        codec.encode(value, payloadBuffer)
        val fileBytes = Envelope.write(Envelope.MAGIC_VALUE, payloadBuffer.readByteArray())

        beforeAdmissionTestGate()
        mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                withContext(ioContext) {
                    sweepTemporaryDirectoriesIfNeeded()
                    writeFile(identity, fileBytes)
                }
                bumpSignal(identity)
            }
        }
    }

    public override suspend fun delete(key: K) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        beforeAdmissionTestGate()
        mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                withContext(ioContext) {
                    sweepTemporaryDirectoriesIfNeeded()
                    val path = pathFor(identity)
                    SystemFileSystem.delete(path, mustExist = false)
                    bestEffortDelete(FileNames.corruptSibling(path))
                }
                bumpSignal(identity)
            }
        }
    }

    public override suspend fun deleteNamespace(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        FileNames.requireValidComponents(namespaceValue, "")

        beforeAdmissionTestGate()
        mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                val trashedPath =
                    withContext(ioContext) {
                        sweepTemporaryDirectoriesIfNeeded()
                        moveToTrashIfExists(
                            FileNames.namespaceDirectory(valuesDirectory, namespaceValue),
                        )
                    }
                bumpNamespaceSignals(namespaceValue)
                withContext(ioContext) {
                    trashedPath?.let(::purgeRecursively)
                }
            }
        }
    }

    public override suspend fun deleteAll() {
        beforeAdmissionTestGate()
        mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                val trashedPath =
                    withContext(ioContext) {
                        sweepTemporaryDirectoriesIfNeeded()
                        moveToTrashIfExists(valuesDirectory)
                    }
                bumpAllSignals()
                withContext(ioContext) {
                    trashedPath?.let(::purgeRecursively)
                }
            }
        }
    }

    private suspend fun readCurrentRow(identity: KeyIdentity): V? {
        while (true) {
            val snapshot =
                mutex.withLock {
                    withContext(ioContext) {
                        sweepTemporaryDirectoriesIfNeeded()
                        readSnapshot(identity)
                    }
                } ?: return null

            try {
                val payload = Buffer()
                payload.write(snapshot.payload)
                return codec.decode(payload)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (corruptionPolicy == FileCorruptionPolicy.PROPAGATE) throw failure
                val quarantined =
                    mutex.withLock {
                        withContext(ioContext) {
                            sweepTemporaryDirectoriesIfNeeded()
                            quarantineIfUnchanged(identity, snapshot.fileBytes)
                        }
                    }
                if (quarantined) return null
            }
        }
    }

    private fun sweepTemporaryDirectoriesIfNeeded() {
        if (temporaryDirectoriesSwept) return
        sweepTemporaryDirectories(temporaryDirectory, trashDirectory)
        temporaryDirectoriesSwept = true
    }

    private fun moveToTrashIfExists(path: Path): Path? {
        if (!SystemFileSystem.exists(path)) return null
        val trashedPath = Path(trashDirectory, temporaryNames.nextName())
        atomicReplace(path, trashedPath)
        return trashedPath
    }

    private fun readSnapshot(identity: KeyIdentity): FileSnapshot? {
        val path = pathFor(identity)
        if (!SystemFileSystem.exists(path)) return null
        val fileBytes =
            SystemFileSystem.source(path).buffered().use { source ->
                source.readByteArray()
            }
        return when (val result = Envelope.read(Envelope.MAGIC_VALUE, fileBytes)) {
            is EnvelopeResult.Valid -> FileSnapshot(fileBytes, result.payload)
            is EnvelopeResult.StructurallyCorrupt -> {
                when (corruptionPolicy) {
                    FileCorruptionPolicy.QUARANTINE -> {
                        quarantine(path)
                        null
                    }
                    FileCorruptionPolicy.PROPAGATE ->
                        throw IOException(
                            "Structural corruption ${result.reason} in value file $path",
                        )
                }
            }
        }
    }

    private fun quarantineIfUnchanged(
        identity: KeyIdentity,
        failedSnapshot: ByteArray,
    ): Boolean {
        val path = pathFor(identity)
        if (!SystemFileSystem.exists(path)) return false
        val currentSnapshot =
            SystemFileSystem.source(path).buffered().use { source ->
                source.readByteArray()
            }
        if (!snapshotsAreEqual(failedSnapshot, currentSnapshot)) return false
        quarantine(path)
        return true
    }

    private fun writeFile(
        identity: KeyIdentity,
        fileBytes: ByteArray,
    ) {
        var temporaryPath: Path? = null
        try {
            ensureDirectories(FileNames.namespaceDirectory(valuesDirectory, identity.namespace))
            ensureDirectories(temporaryDirectory)
            val stagedPath = Path(temporaryDirectory, temporaryNames.nextName())
            temporaryPath = stagedPath
            beforeDiskWriteTestGate()
            SystemFileSystem.sink(stagedPath).buffered().use { sink ->
                sink.write(fileBytes)
            }
            val canonicalPath = pathFor(identity)
            atomicReplace(stagedPath, canonicalPath)
            temporaryPath = null
            bestEffortDelete(FileNames.corruptSibling(canonicalPath))
        } catch (failure: Throwable) {
            temporaryPath?.let(::bestEffortDelete)
            throw failure
        }
    }

    private fun quarantine(path: Path) {
        try {
            atomicReplace(path, FileNames.corruptSibling(path))
        } catch (_: Throwable) {
            bestEffortDelete(path)
        }
    }

    private fun bestEffortDelete(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (_: Throwable) {
            // Cleanup failure does not change the outcome of the canonical operation.
        }
    }

    private fun pathFor(identity: KeyIdentity): Path =
        FileNames.keyPath(valuesDirectory, identity.namespace, identity.canonicalId)

    private fun requireValid(identity: KeyIdentity) {
        FileNames.requireValidComponents(identity.namespace, identity.canonicalId)
    }

    private fun acquireReader(key: KeyIdentity): MutableStateFlow<Long> {
        while (true) {
            val current = activeReaders.value
            val existing = current[key]
            if (existing != null) {
                val updated = current + (key to existing.copy(references = existing.references + 1L))
                if (activeReaders.compareAndSet(current, updated)) return existing.signal
            } else {
                val signal = MutableStateFlow(0L)
                val updated = current + (key to ReaderEntry(signal, references = 1L))
                if (activeReaders.compareAndSet(current, updated)) return signal
            }
        }
    }

    private fun releaseReader(
        key: KeyIdentity,
        signal: MutableStateFlow<Long>,
    ) {
        while (true) {
            val current = activeReaders.value
            val existing = current[key] ?: return
            if (existing.signal !== signal) return
            val updated =
                if (existing.references == 1L) {
                    current - key
                } else {
                    current + (key to existing.copy(references = existing.references - 1L))
                }
            if (activeReaders.compareAndSet(current, updated)) return
        }
    }

    private fun bumpSignal(key: KeyIdentity) {
        activeReaders.value[key]?.signal?.update { version -> version + 1L }
    }

    private fun bumpNamespaceSignals(namespace: String) {
        activeReaders.value.forEach { (key, entry) ->
            if (key.namespace == namespace) {
                entry.signal.update { version -> version + 1L }
            }
        }
    }

    private fun bumpAllSignals() {
        activeReaders.value.values.forEach { entry ->
            entry.signal.update { version -> version + 1L }
        }
    }

    private data class KeyIdentity(
        val namespace: String,
        val canonicalId: String,
    ) {
        constructor(key: StoreKey) : this(key.namespace.value, key.canonicalId())
    }

    private data class ReaderEntry(
        val signal: MutableStateFlow<Long>,
        val references: Long,
    )

    private class FileSnapshot(
        val fileBytes: ByteArray,
        val payload: ByteArray,
    )
}

/**
 * Returns whether [failedSnapshot] and [currentSnapshot] contain the same bytes.
 *
 * This pure comparison lets decode-failure recovery quarantine only the canonical file version
 * that failed to decode.
 */
internal fun snapshotsAreEqual(
    failedSnapshot: ByteArray,
    currentSnapshot: ByteArray,
): Boolean = failedSnapshot.contentEquals(currentSnapshot)
