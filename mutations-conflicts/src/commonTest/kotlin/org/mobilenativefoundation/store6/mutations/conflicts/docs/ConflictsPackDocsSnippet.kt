@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts.docs

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutationStoreBuilder
import org.mobilenativefoundation.store6.mutations.MutatorRegistry
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.conflicts.ConflictsPackBackend
import org.mobilenativefoundation.store6.mutations.conflicts.ConflictsPackKey
import org.mobilenativefoundation.store6.mutations.conflicts.ConflictsPackKeyResolver
import org.mobilenativefoundation.store6.mutations.conflicts.clientWins
import org.mobilenativefoundation.store6.mutations.conflicts.lastWriteWins
import org.mobilenativefoundation.store6.mutations.conflicts.mergeFields
import org.mobilenativefoundation.store6.mutations.conflicts.serverWins
import org.mobilenativefoundation.store6.mutations.conflicts.threeWayMerge
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

private data class Note(
    val title: String,
    val body: String,
    val updatedAtEpochMillis: Long,
)

private data class RenameNote(
    val title: String,
    val freshStampEpochMillis: Long,
)

private object NoteCodec : MutationCodec<Note> {
    override fun encode(value: Note): ByteArray =
        listOf(value.updatedAtEpochMillis, value.title, value.body)
            .joinToString("\u0000")
            .encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): Note {
        val fields = bytes.decodeToString().split('\u0000')
        require(fields.size == 3)
        return Note(
            title = fields[1],
            body = fields[2],
            updatedAtEpochMillis = fields[0].toLong(),
        )
    }
}

private object RenameNoteCodec : MutationCodec<RenameNote> {
    override fun encode(value: RenameNote): ByteArray =
        "${value.freshStampEpochMillis}\u0000${value.title}".encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): RenameNote {
        val fields = bytes.decodeToString().split('\u0000')
        require(fields.size == 2)
        return RenameNote(
            title = fields[1],
            freshStampEpochMillis = fields[0].toLong(),
        )
    }
}

// docs:snippet:mutations-conflicts-pack-server-wins
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installServerWins() {
    conflicts {
        serverWins()
    }
}
// docs:snippet:end

// docs:snippet:mutations-conflicts-pack-client-wins
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installClientWins() {
    conflicts {
        clientWins()
    }
}
// docs:snippet:end

// docs:snippet:mutations-conflicts-pack-last-write-wins
private fun <K : StoreKey> noteMutators(): MutatorRegistry<K, Note> =
    mutatorRegistry {
        update(
            id = "rename-note",
            version = 1,
            codec = RenameNoteCodec,
            stales = { key, _ ->
                StaleSet(keys = setOf(key), namespaces = emptySet())
            },
        ) { note, rename ->
            note.copy(
                title = rename.title,
                updatedAtEpochMillis = rename.freshStampEpochMillis,
            )
        }
    }

private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installLastWriteWins() {
    conflicts {
        lastWriteWins { note -> note.updatedAtEpochMillis }
    }
}
// docs:snippet:end

// docs:snippet:mutations-conflicts-pack-three-way
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installThreeWayMerge() {
    conflicts {
        threeWayMerge { base, mine, theirs ->
            theirs.copy(
                title = if (base == null || mine.title != base.title) mine.title else theirs.title,
                body = if (base == null || mine.body != base.body) mine.body else theirs.body,
                updatedAtEpochMillis =
                    maxOf(mine.updatedAtEpochMillis, theirs.updatedAtEpochMillis),
            )
        }
    }
}
// docs:snippet:end

// docs:snippet:mutations-conflicts-pack-fields
private fun <K : StoreKey> MutationStoreBuilder<K, Note>.installFieldMerge() {
    conflicts {
        mergeFields {
            field(
                get = Note::title,
                set = { note, title -> note.copy(title = title) },
            )
            field(
                get = Note::body,
                set = { note, body -> note.copy(body = body) },
                combine = { _, mine, theirs -> "$mine\n$theirs" },
            )
            field(
                get = Note::updatedAtEpochMillis,
                set = { note, stamp -> note.copy(updatedAtEpochMillis = stamp) },
                combine = { _, mine, theirs -> maxOf(mine, theirs) },
            )
        }
    }
}
// docs:snippet:end

class ConflictsPackDocsSnippetTest {

    @Test
    fun serverWinsSnippet_buildsStore() = runTest {
        val store = openNoteStore(emptyNoteRegistry()) { installServerWins() }
        store.close()
    }

    @Test
    fun clientWinsSnippet_buildsStore() = runTest {
        val store = openNoteStore(emptyNoteRegistry()) { installClientWins() }
        store.close()
    }

    @Test
    fun lastWriteWinsSnippet_buildsStore() = runTest {
        val store = openNoteStore(noteMutators()) { installLastWriteWins() }
        store.close()
    }

    @Test
    fun threeWaySnippet_buildsStore() = runTest {
        val store = openNoteStore(emptyNoteRegistry()) { installThreeWayMerge() }
        store.close()
    }

    @Test
    fun fieldsSnippet_buildsStore() = runTest {
        val store = openNoteStore(emptyNoteRegistry()) { installFieldMerge() }
        store.close()
    }
}

private fun emptyNoteRegistry(): MutatorRegistry<ConflictsPackKey, Note> = mutatorRegistry {}

private fun openNoteStore(
    registry: MutatorRegistry<ConflictsPackKey, Note>,
    configure: MutationStoreBuilder<ConflictsPackKey, Note>.() -> Unit,
): MutationStore<ConflictsPackKey, Note> {
    val server = ConflictsPackBackend<Note>()
    return mutationStore(
        registry = registry,
        server = server,
        keyResolver = ConflictsPackKeyResolver,
        valueCodecVersion = 1,
        valueCodec = NoteCodec,
    ) {
        fetcherOfResult { key -> server.loadResult(key) }
        configure()
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
