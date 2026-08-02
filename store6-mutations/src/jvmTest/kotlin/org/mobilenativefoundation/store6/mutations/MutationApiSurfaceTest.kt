package org.mobilenativefoundation.store6.mutations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R1-13: the committed KLib declaration exposes the ruled `MutationJournalStorage` seam, but no
 * overlay setter, no [org.mobilenativefoundation.store6.core.seam.StoreRuntime], no
 * [org.mobilenativefoundation.store6.core.seam.StoreWriteHandle], no core `StoreBuilder` door
 * through `mutationStore`, and no SQLDelight driver or `Transacter` type — the narrowed-facade
 * invariant at dump level.
 * The dump path arrives via the `store6.mutations.apiDumpDir` system property injected by the
 * module build file; a missing property or file fails loudly rather than passing vacuously.
 */
class MutationApiSurfaceTest {
    @Test
    fun apiDumpContainsNoOverlaySetterRuntimeOrWriteHandleExposure() {
        val dumpDir =
            System.getProperty("store6.mutations.apiDumpDir")
                ?: fail("store6.mutations.apiDumpDir system property is not set; check store6-mutations/build.gradle.kts jvmTest configuration.")
        val dump = File(dumpDir, "store6-mutations.klib.api")
        assertTrue(dump.isFile, "Committed KLib dump not found at ${dump.absolutePath}.")
        val text = dump.readText()

        assertTrue(
            text.contains("MutationJournalStorage"),
            "Committed KLib dump is missing the ruled MutationJournalStorage seam.",
        )

        val banned =
            listOf(
                "StoreWriteHandle",
                "StoreRuntime",
                "core/StoreBuilder",
                "overlay(",
                "SqlDriver",
                "Transacter",
            )
        val violations =
            banned.filter { needle -> text.contains(needle) }
        assertTrue(
            violations.isEmpty(),
            "Committed KLib dump exposes banned seam surface: $violations",
        )
    }
}
