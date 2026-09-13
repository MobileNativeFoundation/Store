package org.mobilenativefoundation.store6.mutations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The committed KLib declaration exposes the `MutationJournalStorage` seam, but no
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
                ?: fail("store6.mutations.apiDumpDir system property is not set; check mutations/build.gradle.kts jvmTest configuration.")
        val dump = File(dumpDir, "mutations.klib.api")
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

        val mutation023Banned =
            listOf(
                "backoff",
                "policy",
                "bound",
                "trigger",
            )
        val mutation023Violations =
            mutation023Banned.filter { needle -> text.contains(needle, ignoreCase = true) }
        assertTrue(
            mutation023Violations.isEmpty(),
            "Committed KLib dump exposes a backoff/policy/bound/trigger door: $mutation023Violations",
        )

        val dumpSha256 =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(dump.readBytes())
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
                }
        assertTrue(
            dumpSha256 == "da41e93f200ca0dc66c7e7890a80ff4cfd8617f073e7bc89a00ab0c4fa38c286",
            "Committed KLib dump differs from the da72d908 T0.3 baseline: $dumpSha256",
        )
    }
}
