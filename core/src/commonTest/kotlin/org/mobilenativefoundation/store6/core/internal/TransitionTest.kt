package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalStoreApi::class)
class TransitionTest {
    private fun ticket(): FetchTicket = FetchTicket(CompletableDeferred())

    private fun meta(
        writtenAtEpochMillis: Long = 10L,
        etag: String? = "etag",
    ): StoreMeta = EngineStoreMeta(writtenAtEpochMillis, etag)

    private fun commitFetch(
        ticket: FetchTicket,
        value: Any = "value",
        meta: StoreMeta = meta(),
    ): KeyEvent.CommitFetch =
        KeyEvent.CommitFetch(ticket, value, meta)

    private fun attribution(
        owner: FetchTicket = ticket(),
        value: Any = "resident",
        origin: Origin = Origin.SOT,
        meta: StoreMeta = meta(),
        staleEpochAtCommit: Long = 2L,
    ): AttributionTag =
        AttributionTag(
            owner = owner,
            value = value,
            origin = origin,
            meta = meta,
            staleEpochAtCommit = staleEpochAtCommit,
        )

    @Test
    fun attributionTag_equalPayloadInstances_areIdentityDistinct() {
        val value = Any()
        val meta = meta()
        val first = attribution(value = value, meta = meta)
        val second = attribution(value = value, meta = meta)

        assertNotSame(first, second)
        assertNotEquals(first, second)
    }

    @Test
    fun ensureFetch_whenIdle_launchesWithFreshTicket() {
        val fresh = ticket()

        val result = transition(KeyState.Initial, KeyEvent.EnsureFetch(fresh))

        assertSame(fresh, assertIs<FetchSlot.InFlight>(result.state.fetch).ticket)
        assertSame(fresh, assertIs<KeyEffect.Launch>(result.effect).ticket)
    }

    @Test
    fun ensureFetch_whenInFlight_joinsExistingTicketWithoutChangingState() {
        val existing = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(existing, clearEpochAtLaunch = 0L),
            )

        val result = transition(state, KeyEvent.EnsureFetch(ticket()))

        assertSame(state, result.state)
        assertSame(existing, assertIs<KeyEffect.Join>(result.effect).ticket)
    }

    @Test
    fun settleFetch_withMatchingTicket_returnsToIdle() {
        val current = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 2L),
                staleEpoch = 1L,
                clearEpoch = 2L,
            )

        val result = transition(
            state,
            KeyEvent.SettleFetch(current),
        )

        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(1L, result.state.staleEpoch)
        assertEquals(2L, result.state.clearEpoch)
        assertIs<KeyEffect.Settled>(result.effect)
    }

    @Test
    fun settleFetch_withStaleTicket_preservesCurrentFetch() {
        val current = ticket()
        val state =
            KeyState.Initial.copy(
                fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            )

        val result = transition(state, KeyEvent.SettleFetch(ticket()))

        assertSame(state, result.state)
        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun settleFetch_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, KeyEvent.SettleFetch(ticket()))

        assertSame(KeyState.Initial, result.state)
        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun ensureFetch_recordsClearEpochAtLaunch() {
        val state = KeyState.Initial.copy(clearEpoch = 7L)

        val result = transition(state, KeyEvent.EnsureFetch(ticket()))

        assertEquals(7L, assertIs<FetchSlot.InFlight>(result.state.fetch).clearEpochAtLaunch)
    }

    @Test
    fun commitFetch_matchingTicketAndEpoch_commitsAndSettles() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            staleEpoch = 3L,
        )

        val result = transition(state, commitFetch(current))

        assertIs<KeyEffect.Commit>(result.effect)
        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(3L, result.state.staleEpoch) // epochs preserved
        assertEquals(0L, result.state.clearEpoch)
    }

    @Test
    fun commitFetch_matchingTicketAndEpoch_stampsValueBoundAttributionAtCommitEpoch() {
        val current = ticket()
        val value = Any()
        val meta = meta(writtenAtEpochMillis = 123L, etag = "v2")
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 4L),
            staleEpoch = 7L,
            clearEpoch = 4L,
            readerGen = 9L,
        )

        val result = transition(
            state,
            commitFetch(
                ticket = current,
                value = value,
                meta = meta,
            ),
        )

        val tag = requireNotNull(result.state.attribution)
        assertSame(value, tag.value)
        assertEquals(Origin.FETCHER, tag.origin)
        assertSame(meta, tag.meta)
        assertEquals(7L, tag.staleEpochAtCommit)
        assertEquals(9L, result.state.readerGen)
    }

    @Test
    fun commitFetch_afterClearAdvancedEpoch_isSuperseded() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 0L),
            clearEpoch = 1L,
        )

        val result = transition(state, commitFetch(current))

        assertIs<KeyEffect.Superseded>(result.effect)
        assertSame(state, result.state) // slot kept for the settle path
    }

    @Test
    fun commitFetch_staleTicket_isIgnored() {
        val current = ticket()
        val state = KeyState.Initial.copy(fetch = FetchSlot.InFlight(current, 0L))

        val result = transition(state, commitFetch(ticket()))

        assertIs<KeyEffect.Ignored>(result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitFetch_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, commitFetch(ticket()))

        assertIs<KeyEffect.Ignored>(result.effect)
    }

    @Test
    fun invalidate_bumpsOnlyStaleEpoch_andPreservesReaderGenerationAndAttribution() {
        val tag = attribution()
        val state = KeyState.Initial.copy(readerGen = 5L, attribution = tag)

        val result = transition(state, KeyEvent.Invalidate)

        assertIs<KeyEffect.Invalidated>(result.effect)
        assertEquals(1L, result.state.staleEpoch)
        assertEquals(0L, result.state.clearEpoch)
        assertEquals(5L, result.state.readerGen)
        assertSame(tag, result.state.attribution)
        assertIs<FetchSlot.Idle>(result.state.fetch)
    }

    @Test
    fun clear_bumpsBothEpochsAndReaderGeneration_revokesAttribution() {
        val state = KeyState.Initial.copy(
            staleEpoch = 2L,
            clearEpoch = 3L,
            readerGen = 4L,
            attribution = attribution(),
        )

        val result = transition(state, KeyEvent.Clear)

        assertIs<KeyEffect.ClearResidence>(result.effect)
        assertEquals(3L, result.state.staleEpoch)
        assertEquals(4L, result.state.clearEpoch)
        assertEquals(5L, result.state.readerGen)
        assertNull(result.state.attribution)
    }

    @Test
    fun settleFetch_preservesEpochs() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 2L),
            staleEpoch = 5L,
            clearEpoch = 2L,
        )

        val result = transition(state, KeyEvent.SettleFetch(current))

        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(5L, result.state.staleEpoch) // the Initial-reset bug must not return
        assertEquals(2L, result.state.clearEpoch)
    }

    @Test
    fun settleFetch_afterClearAdvancedEpoch_isSupersededAndSettled() {
        val current = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(current, clearEpochAtLaunch = 1L),
            staleEpoch = 4L,
            clearEpoch = 2L,
        )

        val result = transition(state, KeyEvent.SettleFetch(current))

        assertIs<KeyEffect.Superseded>(result.effect)
        assertIs<FetchSlot.Idle>(result.state.fetch)
        assertEquals(4L, result.state.staleEpoch)
        assertEquals(2L, result.state.clearEpoch)
    }

    @Test
    fun settleFetch_afterCommitAlreadySettled_isIgnored() {
        val current = ticket()
        val committed = transition(
            KeyState.Initial.copy(fetch = FetchSlot.InFlight(current, 0L)),
            commitFetch(current),
        )

        val result = transition(committed.state, KeyEvent.SettleFetch(current))

        assertIs<KeyEffect.Ignored>(result.effect)
        assertSame(committed.state, result.state)
    }

    @Test
    fun commitDeleted_matchingTicketAndEpoch_settlesAndRequestsDeleteCommit() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 0L),
            readerGen = 6L,
            attribution = attribution(),
        )
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.CommitDelete, result.effect)
        assertEquals(FetchSlot.Idle, result.state.fetch)
        assertEquals(1L, result.state.clearEpoch)
        assertEquals(0L, result.state.staleEpoch) // no stale bump: deletion must not drive refetch loops
        assertEquals(7L, result.state.readerGen)
        assertNull(result.state.attribution)
    }

    @Test
    fun commitDeleted_afterClearAdvancedEpoch_isSuperseded() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 0L),
            staleEpoch = 1L,
            clearEpoch = 1L,
        )
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.Superseded, result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitDeleted_staleTicket_isIgnored() {
        val state = KeyState.Initial.copy(fetch = FetchSlot.InFlight(ticket(), clearEpochAtLaunch = 0L))
        val result = transition(state, KeyEvent.CommitDeleted(ticket()))
        assertEquals(KeyEffect.Ignored, result.effect)
        assertSame(state, result.state)
    }

    @Test
    fun commitDeleted_whenIdle_isIgnored() {
        val result = transition(KeyState.Initial, KeyEvent.CommitDeleted(ticket()))
        assertEquals(KeyEffect.Ignored, result.effect)
        assertSame(KeyState.Initial, result.state)
    }

    @Test
    fun commitDeleted_preservesStaleEpochUnderPriorInvalidations() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 2L),
            staleEpoch = 5L,
            clearEpoch = 2L,
            readerGen = 8L,
            attribution = attribution(),
        )
        val result = transition(state, KeyEvent.CommitDeleted(owner))
        assertEquals(KeyEffect.CommitDelete, result.effect)
        assertEquals(5L, result.state.staleEpoch)
        assertEquals(3L, result.state.clearEpoch)
        assertEquals(9L, result.state.readerGen)
        assertNull(result.state.attribution)
    }

    @Test
    fun commitRevalidated_matchingTicketAndEpoch_settlesAndRevokesAttribution() {
        val owner = ticket()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 2L),
            staleEpoch = 5L,
            clearEpoch = 2L,
            readerGen = 8L,
            attribution = attribution(),
        )

        val result = transition(state, KeyEvent.CommitRevalidated(owner))

        assertEquals(KeyEffect.CommitRevalidation, result.effect)
        assertEquals(FetchSlot.Idle, result.state.fetch)
        assertEquals(5L, result.state.staleEpoch)
        assertEquals(2L, result.state.clearEpoch)
        assertEquals(8L, result.state.readerGen)
        assertNull(result.state.attribution)
    }

    @Test
    fun commitRevalidated_afterClearAdvancedEpoch_isSupersededWithoutRevokingAttribution() {
        val owner = ticket()
        val tag = attribution()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(owner, clearEpochAtLaunch = 1L),
            clearEpoch = 2L,
            attribution = tag,
        )

        val result = transition(state, KeyEvent.CommitRevalidated(owner))

        assertEquals(KeyEffect.Superseded, result.effect)
        assertSame(state, result.state)
        assertSame(tag, result.state.attribution)
    }

    @Test
    fun commitRevalidated_staleTicket_isIgnoredWithoutRevokingAttribution() {
        val tag = attribution()
        val state = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(ticket(), clearEpochAtLaunch = 0L),
            attribution = tag,
        )

        val result = transition(state, KeyEvent.CommitRevalidated(ticket()))

        assertEquals(KeyEffect.Ignored, result.effect)
        assertSame(state, result.state)
        assertSame(tag, result.state.attribution)
    }

    @Test
    fun applyWrite_stampsSotAttribution_preservingSlotAndEpochs() {
        val inFlight = KeyState.Initial.copy(
            fetch = FetchSlot.InFlight(FetchTicket(CompletableDeferred()), 0L),
            staleEpoch = 3L,
            clearEpoch = 1L,
        )
        val meta = EngineStoreMeta(writtenAtEpochMillis = 7L, etag = null)
        val writeTicket = FetchTicket(CompletableDeferred())

        val result = transition(
            inFlight,
            KeyEvent.ApplyWrite(ticket = writeTicket, value = "v", meta = meta),
        )

        assertEquals(KeyEffect.CommitWrite, result.effect)
        assertEquals(inFlight.fetch, result.state.fetch)
        assertEquals(3L, result.state.staleEpoch)
        assertEquals(1L, result.state.clearEpoch)
        val tag = assertNotNull(result.state.attribution)
        assertSame(writeTicket, tag.owner)
        assertEquals(Origin.SOT, tag.origin)
        assertEquals(3L, tag.staleEpochAtCommit)
    }

    @Test
    fun consumeAttribution_whenPresent_returnsThenClearsTag() {
        val tag = attribution()
        val state = KeyState.Initial.copy(attribution = tag)

        val result = transition(state, KeyEvent.ConsumeAttribution(tag))

        assertSame(tag, assertIs<KeyEffect.Consumed>(result.effect).tag)
        assertNull(result.state.attribution)
    }

    @Test
    fun consumeAttribution_whenAbsent_returnsNullWithoutChangingStateInstance() {
        val state = KeyState.Initial.copy(readerGen = 4L)

        val result = transition(state, KeyEvent.ConsumeAttribution(null))

        assertNull(assertIs<KeyEffect.Consumed>(result.effect).tag)
        assertSame(state, result.state)
    }

    @Test
    fun consumeAttribution_observedBeforeCurrentTag_doesNotConsumeTheLaterTag() {
        val later = attribution()
        val state = KeyState.Initial.copy(attribution = later)

        val result = transition(state, KeyEvent.ConsumeAttribution(observed = null))

        assertNull(assertIs<KeyEffect.Consumed>(result.effect).tag)
        assertSame(state, result.state)
        assertSame(later, result.state.attribution)
    }

    @Test
    fun revokeAttribution_clearsTag() {
        val state = KeyState.Initial.copy(attribution = attribution())

        val result = transition(state, KeyEvent.RevokeAttribution)

        assertEquals(KeyEffect.AttributionRevoked, result.effect)
        assertNull(result.state.attribution)
    }
}
