@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class MutationPresenceTest {
    @Test
    fun presentAbsentAndDecline_areDistinctAndExhaustive() = runTest {
        val present: MutationPresence<String> = MutationPresence.Present("value")
        val absent: MutationPresence<String> = MutationPresence.Absent
        val decline: MutationPresence<String>? = null

        // Sealed exhaustiveness: no else branch compiles or is required.
        val describedPresent =
            when (present) {
                is MutationPresence.Present -> "present:${present.value}"
                MutationPresence.Absent -> "absent"
            }
        assertEquals("present:value", describedPresent)

        val describedAbsent =
            when (absent) {
                is MutationPresence.Present -> "present:${absent.value}"
                MutationPresence.Absent -> "absent"
            }
        assertEquals("absent", describedAbsent)

        // Decline is null and only null; it is never Absent and never a value.
        assertNull(decline)
        assertNotNull(absent)
        assertIs<MutationPresence.Present<String>>(present)
    }

    @Test
    fun presence_isCovariant_andAbsentUnifiesAcrossValueTypes() = runTest {
        // Declaration-site covariance: a Present<String> is a MutationPresence<CharSequence>.
        val widened: MutationPresence<CharSequence> = MutationPresence.Present("value")
        assertIs<MutationPresence.Present<CharSequence>>(widened)

        // Absent is one singleton usable at every value type.
        val stringAbsent: MutationPresence<String> = MutationPresence.Absent
        val intAbsent: MutationPresence<Int> = MutationPresence.Absent
        assertSame<Any>(stringAbsent, intAbsent)
    }

    @Test
    fun nullProjectorResult_meansDeclineOnly_neverDeletion() = runTest {
        lateinit var declining: MutatorRef<MutationsTestKey, String, String>
        lateinit var deleting: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                declining =
                    mutator(
                        id = "declining",
                        version = 1,
                        codec = Utf8StringCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { _, _ -> null }
                deleting = delete("deleting") { _, _ -> emptyStaleSet() }
            }

        val declined =
            registry.registrations
                .getValue(declining.id)
                .project(MutationPresence.Present("base"), "args")
        val deleted =
            registry.registrations
                .getValue(deleting.id)
                .project(MutationPresence.Present("base"), Unit)

        // Null is decline and nothing else; deletion is the distinct non-null Absent.
        assertNull(declined)
        assertSame(MutationPresence.Absent, assertNotNull(deleted))
    }
}

private class Utf8StringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

private fun emptyStaleSet(): StaleSet<MutationsTestKey> =
    StaleSet(keys = emptySet(), namespaces = emptySet<StoreNamespace>())

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
