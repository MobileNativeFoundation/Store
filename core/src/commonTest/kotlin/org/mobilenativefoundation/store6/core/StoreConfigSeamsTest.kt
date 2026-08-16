package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.WallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class StoreConfigSeamsTest {
    private class FixedWallClock(var now: Long) : WallClock {
        override fun nowEpochMillis(): Long = now
    }

    @Test
    fun wallClock_drivesMaxAgePlanning() = runTest {
        var fetches = 0
        val clock = FixedWallClock(now = 1_000L)
        val store =
            store<TestKey, String> {
                fetcher {
                    fetches++
                    "v$fetches"
                }
                wallClock(clock)
            }

        assertEquals("v1", store.get(TestKey("1"), Freshness.MaxAge(5.seconds)))
        assertEquals("v1", store.get(TestKey("1"), Freshness.MaxAge(5.seconds)))
        assertEquals(1, fetches)
        clock.now = 7_000L
        assertEquals("v2", store.get(TestKey("1"), Freshness.MaxAge(5.seconds)))
        assertEquals(2, fetches)
        store.close()
    }

    @Test
    fun freshnessValidator_alwaysSkip_neverInvokesFetcher() = runTest {
        var fetches = 0
        val store =
            store<TestKey, String> {
                fetcher {
                    fetches++
                    "v"
                }
                freshnessValidator(
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan = FetchPlan.Skip
                    },
                )
            }

        val failure = assertFailsWith<StoreException> { store.get(TestKey("1")) }
        assertIs<StoreError.Missing>(failure.error)
        assertEquals(0, fetches)
        store.close()
    }

    @Test
    fun freshnessValidator_alwaysFetch_refetchesEveryGet() = runTest {
        var fetches = 0
        val store =
            store<TestKey, String> {
                fetcher {
                    fetches++
                    "v$fetches"
                }
                freshnessValidator(
                    object : FreshnessValidator {
                        override fun plan(context: FreshnessContext): FetchPlan =
                            FetchPlan.Fetch(servesResidentWhileFetching = false)
                    },
                )
            }

        assertEquals("v1", store.get(TestKey("1")))
        assertEquals("v2", store.get(TestKey("1")))
        assertEquals(2, fetches)
        store.close()
    }

    @Test
    fun bookkeeper_sharedAcrossRestart_preservesDurableStaleness() = runTest {
        var fetches = 0
        val sharedBookkeeper = InMemoryBookkeeper()
        val sharedSot = InMemorySourceOfTruth<TestKey, String>()
        val first =
            store<TestKey, String> {
                fetcher {
                    fetches++
                    "v$fetches"
                }
                persistence(sharedSot)
                bookkeeper(sharedBookkeeper)
            }

        assertEquals("v1", first.get(TestKey("1")))
        first.invalidate(TestKey("1"))
        first.close()

        val second =
            store<TestKey, String> {
                fetcher {
                    fetches++
                    "v$fetches"
                }
                persistence(sharedSot)
                bookkeeper(sharedBookkeeper)
            }

        assertEquals("v2", second.get(TestKey("1"), Freshness.MaxAge(5.seconds)))
        assertEquals(2, fetches)
        second.close()
    }
}
