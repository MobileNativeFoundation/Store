@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class StoreTelemetryLoggerTest {
    @Test
    fun formatsEveryEventKindWithMonotonicSequenceAndExactFieldOrder() {
        val clock = TestTimeSource()
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            timeSource = clock,
            emit = { line: String -> lines += line },
        )
        val key = TestKey("users", "user-1")
        val error = TestStoreResults.fetchError("review-gated failure detail")

        logger.onFetchStarted(key)
        clock += 1_500.milliseconds
        logger.onFetchSucceeded(key, 120.milliseconds)
        clock += 500.milliseconds
        logger.onFetchFailed(key, error, 80.milliseconds)
        clock += 1_000.milliseconds
        logger.onServe(key, Origin.FETCHER)
        clock += 250.milliseconds
        logger.onInvalidated(key)
        clock += 250.milliseconds
        logger.onCleared(key)

        assertEquals(
            listOf(
                "store6 v0 seq=1 t_ms=0 evt=fetch_started ns=users key=user-1",
                "store6 v0 seq=2 t_ms=1500 evt=fetch_succeeded ns=users key=user-1 fetch_ms=120",
                "store6 v0 seq=3 t_ms=2000 evt=fetch_failed ns=users key=user-1 fetch_ms=80 error=Fetch",
                "store6 v0 seq=4 t_ms=3000 evt=serve ns=users key=user-1 origin=FETCHER",
                "store6 v0 seq=5 t_ms=3250 evt=invalidate ns=users key=user-1",
                "store6 v0 seq=6 t_ms=3500 evt=clear ns=users key=user-1",
            ),
            lines,
        )
    }

    @Test
    fun quotesAndEscapesArbitraryIdentityValues() {
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = { line: String -> lines += line },
        )

        logger.onFetchStarted(TestKey("user space", "user 1"))
        logger.onFetchStarted(TestKey("user space", "user\"1"))
        logger.onFetchStarted(TestKey("user space", "user\\1"))
        logger.onFetchStarted(TestKey("user space", "user\n1"))
        logger.onFetchStarted(TestKey("user space", "user=1"))
        logger.onFetchStarted(TestKey("user space", "user\r1"))
        logger.onFetchStarted(TestKey("user space", "user\t1"))

        assertEquals(
            listOf(
                """store6 v0 seq=1 t_ms=0 evt=fetch_started ns="user space" key="user 1"""",
                """store6 v0 seq=2 t_ms=0 evt=fetch_started ns="user space" key="user\"1"""",
                """store6 v0 seq=3 t_ms=0 evt=fetch_started ns="user space" key="user\\1"""",
                """store6 v0 seq=4 t_ms=0 evt=fetch_started ns="user space" key="user\n1"""",
                """store6 v0 seq=5 t_ms=0 evt=fetch_started ns="user space" key="user=1"""",
                """store6 v0 seq=6 t_ms=0 evt=fetch_started ns="user space" key="user\r1"""",
                """store6 v0 seq=7 t_ms=0 evt=fetch_started ns="user space" key="user\t1"""",
            ),
            lines,
        )
    }

    @Test
    fun escapesControlCharactersAndUnicodeLineSeparatorsWithLowercaseHex() {
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = { line: String -> lines += line },
        )

        logger.onFetchStarted(
            TestKey(
                "ns\u0000\u0085\u009F\u2028\u2029",
                "id\"\\\n\r\t\u0008\u000B\u000C\u001B\u001F\u007F",
            ),
        )

        assertEquals(
            listOf(
                """store6 v0 seq=1 t_ms=0 evt=fetch_started ns="ns\u0000\u0085\u009f\u2028\u2029" key="id\"\\\n\r\t\u0008\u000b\u000c\u001b\u001f\u007f"""",
            ),
            lines,
        )
    }

    @Test
    fun emittedLineContainsNoRawControlsOrUnicodeLineSeparators() {
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = { line: String -> lines += line },
        )

        logger.onFetchStarted(
            TestKey(
                "ns\u0000\u0085\u009F\u2028\u2029",
                "id\u0008\u000B\u000C\u001B\u001F\u007F",
            ),
        )

        val rawUnsafeCodePoints = lines.single()
            .filter { character ->
                character.code in 0x00..0x1F ||
                    character.code in 0x7F..0x9F ||
                    character == '\u2028' ||
                    character == '\u2029'
            }
            .map { it.code }
        assertEquals(emptyList(), rawUnsafeCodePoints)
    }

    @Test
    fun customLabelIsHonored() {
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            label = "inventory",
            timeSource = TestTimeSource(),
            emit = { line: String -> lines += line },
        )

        logger.onCleared(TestKey("users", "user-1"))

        assertEquals(
            listOf("inventory v0 seq=1 t_ms=0 evt=clear ns=users key=user-1"),
            lines,
        )
    }

    @Test
    fun labelMustBeASafeStructuralToken() {
        val invalidLabels = listOf(
            "",
            " ",
            "store 6",
            "store\t6",
            "store\n6",
            "store\u0000",
            "store\u007F6",
            "store\"6",
            "store=6",
            "store\\6",
        )

        for (label in invalidLabels) {
            assertFailsWith<IllegalArgumentException>("label=$label") {
                StoreTelemetryLogger(label = label)
            }
        }
    }

    @Test
    fun failedEventsUseAllSixLiteralVariantNamesWithoutDiagnosticPayloads() {
        val key = TestKey("users", "user-1")
        val diagnostics = listOf(
            "fetch diagnostic",
            "fetch cause diagnostic",
            "persistence diagnostic",
            "conversion diagnostic",
            "freshness diagnostic",
            "conflict diagnostic",
            "missing diagnostic",
        )
        val cases = listOf(
            ErrorCase(
                "Fetch",
                TestStoreResults.fetchError(
                    diagnostics[0],
                    IllegalStateException(diagnostics[1]),
                ),
            ),
            ErrorCase("Persistence", TestStoreResults.persistenceError(diagnostics[2])),
            ErrorCase("Conversion", TestStoreResults.conversionError(diagnostics[3])),
            ErrorCase(
                "FreshnessUnsatisfiable",
                TestStoreResults.freshnessUnsatisfiable(diagnostics[4]),
            ),
            ErrorCase("Conflict", TestStoreResults.conflict(null, diagnostics[5])),
            ErrorCase("Missing", TestStoreResults.missing(key, diagnostics[6])),
        )
        val lines = mutableListOf<String>()
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = { line: String -> lines += line },
        )

        assertEquals(
            cases.map { it.name },
            cases.map { storeErrorV0Name(it.error) },
        )
        cases.forEachIndexed { index, case ->
            logger.onFetchFailed(key, case.error, (index + 1).milliseconds)
        }

        assertEquals(
            cases.mapIndexed { index, case ->
                "store6 v0 seq=${index + 1} t_ms=0 evt=fetch_failed ns=users key=user-1 " +
                    "fetch_ms=${index + 1} error=${case.name}"
            },
            lines,
        )
        diagnostics.forEach { diagnostic ->
            assertFalse(lines.any { diagnostic in it }, diagnostic)
        }
    }

    @Test
    fun throwingEmitterNeverEscapesAnyTelemetryHandler() {
        var attempts = 0
        val logger = StoreTelemetryLogger(
            timeSource = TestTimeSource(),
            emit = {
                attempts += 1
                error("emitter failure")
            },
        )
        val key = TestKey("users", "user-1")
        val failure = TestStoreResults.fetchError("fetch failed")

        logger.onFetchStarted(key)
        logger.onFetchSucceeded(key, 1.milliseconds)
        logger.onFetchFailed(key, failure, 1.milliseconds)
        logger.onServe(key, Origin.MEMORY)
        logger.onInvalidated(key)
        logger.onCleared(key)

        assertEquals(6, attempts)
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private data class ErrorCase(
        val name: String,
        val error: StoreError,
    )
}
