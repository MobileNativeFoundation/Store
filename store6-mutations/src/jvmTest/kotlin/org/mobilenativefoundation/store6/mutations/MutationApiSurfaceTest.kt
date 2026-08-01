package org.mobilenativefoundation.store6.mutations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R1-13: the committed KLib declaration exposes no overlay setter, no [org.mobilenativefoundation.store6.core.seam.StoreRuntime],
 * no [org.mobilenativefoundation.store6.core.seam.StoreWriteHandle], and no core
 * `StoreBuilder` door through `mutationStore` — the narrowed-facade invariant at dump level.
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

        val banned =
            listOf(
                "StoreWriteHandle",
                "StoreRuntime",
                "core/StoreBuilder",
                "overlay(",
            )
        val violations =
            banned.filter { needle -> text.contains(needle) }
        assertTrue(
            violations.isEmpty(),
            "Committed KLib dump exposes banned seam surface: $violations",
        )
    }
}
