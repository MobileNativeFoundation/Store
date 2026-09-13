@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DrainBackoffTest {
    @Test
    fun defaults_are30sTimes2CappedAt1h() {
        val backoff = DrainBackoff()
        assertEquals(30.seconds, backoff.delayFor(1))
        assertEquals(60.seconds, backoff.delayFor(2))
        assertEquals(1.hours, backoff.delayFor(8)) // 30s * 2^7 = 64 min, capped at 1 h
        assertEquals(1.hours, backoff.delayFor(20))
    }

    @Test
    fun multiplierOne_isConstantFloor() {
        val backoff =
            DrainBackoff(initialDelay = 10.seconds, multiplier = 1.0, maxDelay = 1.hours)
        assertEquals(10.seconds, backoff.delayFor(1))
        assertEquals(10.seconds, backoff.delayFor(9))
    }

    @Test
    fun validation_rejectsZeroInitialNonFiniteAndInvertedBounds() {
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(initialDelay = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(initialDelay = (-1).seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(multiplier = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(multiplier = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(initialDelay = 10.minutes, maxDelay = 1.minutes)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(maxDelay = Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff().delayFor(0)
        }
    }

    @Test
    fun requestAndConstraintDefaults() {
        val constraints = DrainConstraints()
        assertEquals(true, constraints.requiresNetwork)
        assertEquals(false, constraints.requiresCharging)
        val policy = DrainPolicy()
        assertEquals(true, policy.drainOnEnqueue)
    }
}
