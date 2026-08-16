@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemoUiStateTest {
    private fun data(name: String, stale: Boolean = false, refreshing: Boolean = false): StoreResult.Data<User> =
        TestStoreResults.data(
            value = User("1", name), origin = Origin.FETCHER, isStale = stale, refreshing = refreshing,
        )

    @Test fun initialLoadingShowsPlaceholderOnly() {
        val ui = deriveDemoUiState(TestStoreResults.loading(), previousData = null)
        assertTrue(ui.showLoadingPlaceholder)
        assertNull(ui.card)
        assertFalse(ui.showSpinner)
        assertNull(ui.errorBanner)
    }

    @Test fun refreshingDataShowsSpinnerOverContent() {
        val ui = deriveDemoUiState(data("alice", refreshing = true), previousData = null)
        assertNotNull(ui.card)
        assertTrue(ui.showSpinner)
        assertFalse(ui.showLoadingPlaceholder)
    }

    @Test fun loadingAfterDataRetainsCardAndSpins() {
        val prev = data("alice")
        val ui = deriveDemoUiState(TestStoreResults.loading(), previousData = prev)
        assertSame(prev, ui.card)
        assertTrue(ui.showSpinner)
        assertFalse(ui.showLoadingPlaceholder)
    }

    @Test fun staleCardShowsBadge() {
        val ui = deriveDemoUiState(data("alice", stale = true), previousData = null)
        assertTrue(ui.showStaleBadge)
    }

    @Test fun servedStaleErrorBannersOverRetainedCard() {
        val prev = data("alice", stale = true)
        val error = TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = true)
        val ui = deriveDemoUiState(error, previousData = prev)
        assertSame(prev, ui.card)
        assertEquals("Refresh failed — showing stale data", ui.errorBanner)
        assertTrue(ui.showStaleBadge)
        assertNull(ui.emptyError)
    }

    @Test fun freshErrorOverRetainedCardBannersWithoutTheStaleWording() {
        val prev = data("alice")
        val error = TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = false)
        val ui = deriveDemoUiState(error, previousData = prev)
        assertSame(prev, ui.card)
        assertEquals("Refresh failed", ui.errorBanner)
        assertFalse(ui.showStaleBadge)
        assertNull(ui.emptyError)
    }

    @Test fun errorWithNoLocalValueSurfacesEmptyError() {
        val error = TestStoreResults.error(TestStoreResults.fetchError("boom"), servedStale = false)
        val ui = deriveDemoUiState(error, previousData = null)
        assertNull(ui.card)
        assertNull(ui.errorBanner)
        assertNotNull(ui.emptyError)
    }
}
