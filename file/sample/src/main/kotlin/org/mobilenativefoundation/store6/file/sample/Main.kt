@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file.sample

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.file.FileBookkeeper
import org.mobilenativefoundation.store6.file.FileSourceOfTruth
import org.mobilenativefoundation.store6.file.Utf8StringFileCodec

private val notesNamespace = StoreNamespace("notes")

private class NoteKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = notesNamespace

    override fun canonicalId(): String = id
}

private class CountingFetcher {
    var count: Int = 0
        private set

    suspend fun fetch(key: NoteKey): String {
        count += 1
        return "note-${key.id}-rev-$count"
    }
}

public fun main(): Unit =
    runBlocking {
        val root = Files.createTempDirectory("store6-file-sample-").toFile()
        println("File walkthrough directory: ${root.absolutePath}")

        try {
            val directory = Path(root.absolutePath)
            val key = NoteKey("1")

            val persistFetcher = CountingFetcher()
            val persistStore = createStore(directory, persistFetcher)
            try {
                val fetched =
                    persistStore
                        .stream(key)
                        .first { frame -> frame is StoreResult.Data }
                        .requireData("PERSIST")
                check(fetched.origin == Origin.FETCHER) {
                    "PERSIST: expected FETCHER origin, was ${fetched.origin}"
                }
                check(persistFetcher.count == 1) {
                    "PERSIST: expected fetch count 1 after first read, was ${persistFetcher.count}"
                }

                val served =
                    persistStore
                        .stream(key)
                        .first { frame -> frame is StoreResult.Data }
                        .requireData("PERSIST")
                check(persistFetcher.count == 1) {
                    "PERSIST: expected fetch count 1 after second read, was ${persistFetcher.count}"
                }
                check(served.value == fetched.value) {
                    "PERSIST: second read value ${served.value} did not match ${fetched.value}"
                }
                println(
                    "PERSIST: fetched once and persisted; second read served without refetch " +
                        "(value=${served.value}, fetches=1)",
                )
            } finally {
                persistStore.close()
            }

            val rebuildFetcher = CountingFetcher()
            val rebuildStore = createStore(directory, rebuildFetcher)
            try {
                val restarted =
                    rebuildStore
                        .stream(key)
                        .first { frame -> frame is StoreResult.Data }
                        .requireData("SERVE-WITHOUT-REFETCH AFTER REBUILD")
                check(restarted.origin == Origin.SOT) {
                    "SERVE-WITHOUT-REFETCH AFTER REBUILD: expected SOT origin, was ${restarted.origin}"
                }
                check(rebuildFetcher.count == 0) {
                    "SERVE-WITHOUT-REFETCH AFTER REBUILD: expected fetch count 0, was ${rebuildFetcher.count}"
                }
                println(
                    "SERVE-WITHOUT-REFETCH AFTER REBUILD: served persisted value without refetch " +
                        "(value=${restarted.value}, fetches=0)",
                )

                rebuildStore.invalidateNamespace(notesNamespace)
            } finally {
                rebuildStore.close()
            }

            val invalidationFetcher = CountingFetcher()
            val invalidationStore = createStore(directory, invalidationFetcher)
            try {
                val refreshed =
                    invalidationStore
                        .stream(key)
                        .first { frame ->
                            frame is StoreResult.Data && frame.origin == Origin.FETCHER
                        }
                        .requireData("DURABLE INVALIDATION ACROSS REBUILD")
                check(invalidationFetcher.count == 1) {
                    "DURABLE INVALIDATION ACROSS REBUILD: expected fetch count 1, was ${invalidationFetcher.count}"
                }
                println(
                    "DURABLE INVALIDATION ACROSS REBUILD: namespace invalidation forced refetch " +
                        "(value=${refreshed.value}, fetches=1)",
                )
            } finally {
                invalidationStore.close()
            }

            val valueFile = valueFiles(root).singleOrNull()
            check(valueFile != null) {
                "CORRUPTION RECOVERY: expected one persisted value file under ${root.absolutePath}/values"
            }
            valueFile.writeBytes("CORRUPT".encodeToByteArray())

            val corruptionFetcher = CountingFetcher()
            val corruptionStore = createStore(directory, corruptionFetcher)
            try {
                val recovered =
                    corruptionStore
                        .stream(key)
                        .first { frame ->
                            frame is StoreResult.Data && frame.origin == Origin.FETCHER
                        }
                        .requireData("CORRUPTION RECOVERY")
                check(corruptionFetcher.count == 1) {
                    "CORRUPTION RECOVERY: expected fetch count 1, was ${corruptionFetcher.count}"
                }
                println(
                    "CORRUPTION RECOVERY: corrupt value file was treated as absence and refetched " +
                        "(value=${recovered.value}, fetches=1)",
                )
            } finally {
                corruptionStore.close()
            }

            println(
                "Done. Durability lived in the value file and bookkeeper records, " +
                    "not in a retained Store engine.",
            )
        } finally {
            root.deleteRecursively()
        }
    }

private fun createStore(
    directory: Path,
    fetcher: CountingFetcher,
): Store<NoteKey, String> =
    store {
        fetcher { key -> fetcher.fetch(key) }
        persistence(
            FileSourceOfTruth(
                directory = directory,
                codec = Utf8StringFileCodec,
            ),
        )
        bookkeeper(FileBookkeeper(directory))
    }

private fun StoreResult<String>.requireData(checkName: String): StoreResult.Data<String> =
    when (this) {
        is StoreResult.Data -> this
        else -> error("$checkName: expected Data, received $this")
    }

private fun valueFiles(root: File): List<File> {
    val values = File(root, "values")
    if (!values.isDirectory) return emptyList()
    return values.walkTopDown()
        .filter { file -> file.isFile && !file.name.endsWith(".corrupt") }
        .toList()
}
