package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.file.internal.Base32
import org.mobilenativefoundation.store6.file.internal.BookkeeperFormats
import org.mobilenativefoundation.store6.file.internal.Envelope
import org.mobilenativefoundation.store6.file.internal.EnvelopeResult
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.PersistedMeta
import org.mobilenativefoundation.store6.file.internal.PersistedRecord
import org.mobilenativefoundation.store6.file.internal.PersistedWatermarks
import org.mobilenativefoundation.store6.file.internal.UniqueFileNameGenerator
import org.mobilenativefoundation.store6.file.internal.atomicReplace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import org.mobilenativefoundation.store6.file.internal.sweepTemporaryDirectories
import kotlin.coroutines.CoroutineContext

/**
 * Filesystem-backed [Bookkeeper] with process-local status reads.
 *
 * Per-key records and namespace and global watermarks are written through atomic file
 * replacement. A new instance reconstructs its process-local mirror from those files on its first
 * operation.
 *
 * [recordSuccess], [recordFailure], and [forget] update the process-local mirror first and absorb
 * storage failures from their persistence call; cooperative cancellation and virtual-machine
 * failures (`kotlin.Error`) always propagate. While persistence is failing, process-local
 * answers stay correct. A later instance resumes from the last written record for each key, which
 * may be older than the mirror reported, or absent. The maintenance operations persist first
 * and update the mirror only after persistence succeeds, so a persistence failure leaves canonical
 * disk state and the mirror unchanged.
 *
 * [status] answers from the mirror, and the only storage read it makes is that first-operation
 * recovery. A failed recovery propagates to the caller and is never reported as `null` or as
 * fresh metadata. The mirror stays uninitialized, so a later call retries the recovery.
 *
 * Only one live `FileBookkeeper` may use a directory. A [FileSourceOfTruth] may use the same
 * directory because the two classes own disjoint subtrees.
 *
 * `namespace.value` and `canonicalId()` each must be well-formed UTF-16 and at most 159 UTF-8
 * bytes. A component holding an unpaired surrogate, or a longer component, throws
 * [IllegalArgumentException] and applies nothing. Empty strings are valid.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FileBookkeeper internal constructor(
    directory: Path,
    ioContext: CoroutineContext,
    private val beforeAdmissionTestGate: suspend () -> Unit = {},
    private val afterAdmissionTestGate: suspend () -> Unit = {},
    private val beforeDiskWriteTestGate: () -> Unit = {},
) : Bookkeeper {
    public constructor(
        directory: Path,
        ioContext: CoroutineContext = Dispatchers.Default,
    ) : this(
        directory = directory,
        ioContext = ioContext,
        beforeAdmissionTestGate = {},
        afterAdmissionTestGate = {},
        beforeDiskWriteTestGate = {},
    )

    private val ioContext: CoroutineContext = ioContext.minusKey(Job)
    private val bookkeepingDirectory: Path = Path(directory, "bookkeeping")
    private val recordsDirectory: Path = Path(bookkeepingDirectory, "records")
    private val watermarksPath: Path = Path(bookkeepingDirectory, "watermarks")
    private val temporaryDirectory: Path = Path(directory, "bookkeeping-tmp")
    private val trashDirectory: Path = Path(directory, "bookkeeping-trash")
    private val mutex: Mutex = Mutex()
    private val temporaryNames: UniqueFileNameGenerator = UniqueFileNameGenerator()
    private var initialized: Boolean = false
    private var records: HashMap<KeyIdentity, Record> = HashMap()
    private var namespaceStaleWatermarks: HashMap<String, Long> = HashMap()
    private var globalStaleWatermark: Long = 0L
    private var sequence: Long = 0L
    private val watermarkOnlyStatus: KeyStatus =
        KeyStatus(
            meta = null,
            lastSuccessSequence = null,
            lastFailureAtEpochMillis = null,
            consecutiveFailures = 0,
            durablyStale = true,
        )

    public override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = meta,
                    lastSuccessSequence = nextSequence,
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
            records[identity] = nextRecord
            sequence = nextSequence
            val fileBytes = encodeRecord(nextRecord)

            try {
                withContext(ioContext) {
                    persistRecord(identity, fileBytes)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // kotlin.Error propagates unmasked; for storage failures the process-local
                // mirror remains authoritative while persistence is unavailable.
                if (failure is Error) throw failure
            }
        }
    }

    public override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextRecord =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    staleSequence = previous?.staleSequence,
                )
            records[identity] = nextRecord
            val fileBytes = encodeRecord(nextRecord)

            try {
                withContext(ioContext) {
                    persistRecord(identity, fileBytes)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // kotlin.Error propagates unmasked; for storage failures the process-local
                // mirror remains authoritative while persistence is unavailable.
                if (failure is Error) throw failure
            }
        }
    }

    public override suspend fun status(key: StoreKey): KeyStatus? {
        val identity = KeyIdentity(key)
        requireValid(identity)
        return mutex.withLock {
            if (!initialized) {
                // Decision D4: a failed cold recovery propagates to the caller. Absorbing it into
                // a null answer would report a failed read as "no record".
                withContext(NonCancellable) {
                    withContext(ioContext) {
                        recoverFromDiskIfNeeded()
                    }
                }
            }
            statusFromMirror(identity)
        }
    }

    public override suspend fun forget(key: StoreKey) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            records.remove(identity)
            try {
                withContext(ioContext) {
                    deleteRecord(identity)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // kotlin.Error propagates unmasked; for storage failures the process-local
                // mirror remains authoritative while persistence is unavailable.
                if (failure is Error) throw failure
            }
        }
    }

    public override suspend fun markStale(key: StoreKey) {
        val identity = KeyIdentity(key)
        requireValid(identity)

        admittedMutation {
            val previous = records[identity]
            val nextSequence = nextSequenceOrThrow()
            val nextRecord =
                Record(
                    meta = previous?.meta,
                    lastSuccessSequence = previous?.lastSuccessSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence,
                )
            val fileBytes = encodeRecord(nextRecord)

            withContext(ioContext) {
                persistRecord(identity, fileBytes)
            }
            records[identity] = nextRecord
            sequence = nextSequence
        }
    }

    public override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        FileNames.requireValidComponents(namespaceValue, "")

        admittedMutation {
            val nextSequence = nextSequenceOrThrow()
            val nextWatermarks =
                HashMap<String, Long>(namespaceStaleWatermarks.size + 1).also { staged ->
                    staged.putAll(namespaceStaleWatermarks)
                    staged[namespaceValue] = nextSequence
                }
            val fileBytes = encodeWatermarks(nextWatermarks, globalStaleWatermark)

            withContext(ioContext) {
                persistWatermarks(fileBytes)
            }
            namespaceStaleWatermarks = nextWatermarks
            sequence = nextSequence
        }
    }

    public override suspend fun advanceGlobalStaleWatermark() {
        admittedMutation {
            val nextSequence = nextSequenceOrThrow()
            val fileBytes = encodeWatermarks(namespaceStaleWatermarks, nextSequence)

            withContext(ioContext) {
                persistWatermarks(fileBytes)
            }
            globalStaleWatermark = nextSequence
            sequence = nextSequence
        }
    }

    public override suspend fun forgetNamespace(namespace: StoreNamespace) {
        val namespaceValue = namespace.value
        FileNames.requireValidComponents(namespaceValue, "")

        admittedMutation {
            val nextRecords = HashMap<KeyIdentity, Record>(records.size)
            records.forEach { (identity, record) ->
                if (identity.namespace != namespaceValue) {
                    nextRecords[identity] = record
                }
            }
            val trashedPath =
                withContext(ioContext) {
                    moveToTrashIfExists(
                        FileNames.namespaceDirectory(recordsDirectory, namespaceValue),
                    )
                }
            records = nextRecords
            withContext(ioContext) {
                trashedPath?.let(::purgeRecursively)
            }
        }
    }

    public override suspend fun forgetAll() {
        admittedMutation {
            val nextRecords = HashMap<KeyIdentity, Record>()
            val trashedPath =
                withContext(ioContext) {
                    moveToTrashIfExists(recordsDirectory)
                }
            records = nextRecords
            withContext(ioContext) {
                trashedPath?.let(::purgeRecursively)
            }
        }
    }

    private suspend fun <T> admittedMutation(block: suspend () -> T): T {
        beforeAdmissionTestGate()
        return mutex.withLock {
            withContext(NonCancellable) {
                afterAdmissionTestGate()
                withContext(ioContext) {
                    recoverFromDiskIfNeeded()
                }
                block()
            }
        }
    }

    private fun recoverFromDiskIfNeeded() {
        if (initialized) return
        recoverFromDisk()
        initialized = true
    }

    private fun recoverFromDisk() {
        ensureDirectories(recordsDirectory)
        sweepTemporaryDirectories(temporaryDirectory, trashDirectory)

        val recoveredRecords = HashMap<KeyIdentity, Record>()
        for (namespacePath in SystemFileSystem.list(recordsDirectory)) {
            if (namespacePath.name.contains('.')) continue
            val namespace = Base32.decode(namespacePath.name)
            if (namespace == null) {
                quarantine(namespacePath)
                continue
            }
            for (recordPath in SystemFileSystem.list(namespacePath)) {
                if (recordPath.name.contains('.')) continue
                val canonicalId = Base32.decode(recordPath.name)
                val persistedRecord =
                    canonicalId?.let {
                        decodeRecord(readFile(recordPath))
                    }
                if (canonicalId == null || persistedRecord == null) {
                    quarantine(recordPath)
                    continue
                }
                recoveredRecords[KeyIdentity(namespace, canonicalId)] =
                    recordFromPersisted(persistedRecord)
            }
        }

        val recoveredRecordMax = maxRecordSequence(recoveredRecords)
        var recoveredNamespaceWatermarks = HashMap<String, Long>()
        var recoveredGlobalWatermark = 0L
        if (SystemFileSystem.exists(watermarksPath)) {
            val watermarksBytes = readFile(watermarksPath)
            val persistedWatermarks = decodeWatermarks(watermarksBytes)
            if (persistedWatermarks == null) {
                check(recoveredRecordMax < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
                copyCorruptWatermarks(watermarksBytes)
                recoveredGlobalWatermark = recoveredRecordMax + 1L
                persistWatermarks(
                    encodeWatermarks(
                        namespaceWatermarks = emptyMap(),
                        globalWatermark = recoveredGlobalWatermark,
                    ),
                )
            } else {
                recoveredNamespaceWatermarks =
                    HashMap<String, Long>(persistedWatermarks.namespaceWatermarks.size).also { recovered ->
                        recovered.putAll(persistedWatermarks.namespaceWatermarks)
                    }
                recoveredGlobalWatermark = persistedWatermarks.globalStaleWatermark
            }
        }

        var recoveredSequence = maxOf(recoveredRecordMax, recoveredGlobalWatermark)
        for (watermark in recoveredNamespaceWatermarks.values) {
            recoveredSequence = maxOf(recoveredSequence, watermark)
        }
        records = recoveredRecords
        namespaceStaleWatermarks = recoveredNamespaceWatermarks
        globalStaleWatermark = recoveredGlobalWatermark
        sequence = recoveredSequence
    }

    private fun readFile(path: Path): ByteArray =
        SystemFileSystem.source(path).buffered().use { source ->
            source.readByteArray()
        }

    private fun decodeRecord(fileBytes: ByteArray): PersistedRecord? =
        when (val envelope = Envelope.read(Envelope.MAGIC_RECORD, fileBytes)) {
            is EnvelopeResult.Valid -> BookkeeperFormats.decodeRecord(envelope.payload)
            is EnvelopeResult.StructurallyCorrupt -> null
        }

    private fun decodeWatermarks(fileBytes: ByteArray): PersistedWatermarks? =
        when (val envelope = Envelope.read(Envelope.MAGIC_WATERMARKS, fileBytes)) {
            is EnvelopeResult.Valid -> BookkeeperFormats.decodeWatermarks(envelope.payload)
            is EnvelopeResult.StructurallyCorrupt -> null
        }

    private fun recordFromPersisted(persisted: PersistedRecord): Record =
        Record(
            meta =
                persisted.meta?.let { meta ->
                    RecoveredMeta(
                        writtenAtEpochMillis = meta.writtenAtEpochMillis,
                        etag = meta.etag,
                    )
                },
            lastSuccessSequence = persisted.lastSuccessSequence,
            lastFailureAtEpochMillis = persisted.lastFailureAtEpochMillis,
            consecutiveFailures = persisted.consecutiveFailures,
            staleSequence = persisted.staleSequence,
        )

    private fun maxRecordSequence(recoveredRecords: Map<KeyIdentity, Record>): Long {
        var recoveredMax = 0L
        for (record in recoveredRecords.values) {
            recoveredMax = maxOf(
                recoveredMax,
                record.lastSuccessSequence ?: 0L,
                record.staleSequence ?: 0L,
            )
        }
        return recoveredMax
    }

    private fun copyCorruptWatermarks(fileBytes: ByteArray) {
        try {
            SystemFileSystem.sink(FileNames.corruptSibling(watermarksPath)).buffered().use { sink ->
                sink.write(fileBytes)
            }
        } catch (_: Throwable) {
            // The diagnostic copy does not affect replacement of the canonical watermarks file.
        }
    }

    private fun quarantine(path: Path) {
        try {
            atomicReplace(path, FileNames.corruptSibling(path))
        } catch (_: Throwable) {
            bestEffortDelete(path)
        }
    }

    private fun statusFromMirror(identity: KeyIdentity): KeyStatus? {
        val record = records[identity]
        val coveringStaleSequence =
            maxOf(
                record?.staleSequence ?: 0L,
                namespaceStaleWatermarks[identity.namespace] ?: 0L,
                globalStaleWatermark,
            )
        if (record == null && coveringStaleSequence == 0L) return null
        if (record == null) return watermarkOnlyStatus

        val durablyStale = coveringStaleSequence > (record.lastSuccessSequence ?: 0L)
        return record.cachedStatus
            ?.takeIf { cached -> cached.durablyStale == durablyStale }
            ?: KeyStatus(
                meta = record.meta,
                lastSuccessSequence = record.lastSuccessSequence,
                lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                consecutiveFailures = record.consecutiveFailures,
                durablyStale = durablyStale,
            ).also { status ->
                record.cachedStatus = status
            }
    }

    private fun encodeRecord(record: Record): ByteArray {
        val payload =
            BookkeeperFormats.encodeRecord(
                PersistedRecord(
                    meta =
                        record.meta?.let { meta ->
                            PersistedMeta(
                                writtenAtEpochMillis = meta.writtenAtEpochMillis,
                                etag = meta.etag,
                            )
                        },
                    lastSuccessSequence = record.lastSuccessSequence,
                    lastFailureAtEpochMillis = record.lastFailureAtEpochMillis,
                    consecutiveFailures = record.consecutiveFailures,
                    staleSequence = record.staleSequence,
                ),
            )
        return Envelope.write(Envelope.MAGIC_RECORD, payload)
    }

    private fun encodeWatermarks(
        namespaceWatermarks: Map<String, Long>,
        globalWatermark: Long,
    ): ByteArray {
        val payload =
            BookkeeperFormats.encodeWatermarks(
                PersistedWatermarks(
                    globalStaleWatermark = globalWatermark,
                    namespaceWatermarks = namespaceWatermarks,
                ),
            )
        return Envelope.write(Envelope.MAGIC_WATERMARKS, payload)
    }

    private fun persistRecord(
        identity: KeyIdentity,
        fileBytes: ByteArray,
    ) {
        val path = pathFor(identity)
        persistFile(path, fileBytes)
    }

    private fun persistWatermarks(fileBytes: ByteArray) {
        persistFile(watermarksPath, fileBytes)
    }

    private fun persistFile(
        destination: Path,
        fileBytes: ByteArray,
    ) {
        var temporaryPath: Path? = null
        try {
            ensureDirectories(destination.parent!!)
            ensureDirectories(temporaryDirectory)
            val stagedPath = Path(temporaryDirectory, temporaryNames.nextName())
            temporaryPath = stagedPath
            beforeDiskWriteTestGate()
            SystemFileSystem.sink(stagedPath).buffered().use { sink ->
                sink.write(fileBytes)
            }
            atomicReplace(stagedPath, destination)
            temporaryPath = null
        } catch (failure: Throwable) {
            temporaryPath?.let(::bestEffortDelete)
            throw failure
        }
    }

    private fun deleteRecord(identity: KeyIdentity) {
        SystemFileSystem.delete(pathFor(identity), mustExist = false)
    }

    private fun moveToTrashIfExists(path: Path): Path? {
        if (!SystemFileSystem.exists(path)) return null
        val trashedPath = Path(trashDirectory, temporaryNames.nextName())
        atomicReplace(path, trashedPath)
        return trashedPath
    }

    private fun bestEffortDelete(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (_: Throwable) {
            // Cleanup failure does not change the outcome of the canonical operation.
        }
    }

    private fun pathFor(identity: KeyIdentity): Path =
        FileNames.keyPath(recordsDirectory, identity.namespace, identity.canonicalId)

    private fun requireValid(identity: KeyIdentity) {
        FileNames.requireValidComponents(identity.namespace, identity.canonicalId)
    }

    private fun nextSequenceOrThrow(): Long {
        check(sequence < Long.MAX_VALUE) { "Bookkeeper sequence exhausted" }
        return sequence + 1L
    }

    private class Record(
        val meta: StoreMeta?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
        var cachedStatus: KeyStatus? = null,
    )

    private data class RecoveredMeta(
        override val writtenAtEpochMillis: Long,
        override val etag: String?,
    ) : StoreMeta

    private data class KeyIdentity(
        val namespace: String,
        val canonicalId: String,
    ) {
        constructor(key: StoreKey) : this(key.namespace.value, key.canonicalId())
    }
}
