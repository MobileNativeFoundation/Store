@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class MutationMergesFieldsTest {

    @Test
    fun fields_basePresent_neitherChanged_keepsTheirs() {
        val base = Note("base", "body", 1)
        val mine = base.copy()
        val theirs = base.copy()

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(theirs, retryValue(merged))
    }

    @Test
    fun fields_basePresent_onlyMineChanged_appliesMine() {
        val base = Note("base", "body", 1)
        val mine = base.copy(title = "mine")
        val theirs = base.copy()

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals("mine", retryValue(merged).title)
    }

    @Test
    fun fields_basePresent_onlyTheirsChanged_keepsTheirs() {
        val base = Note("base", "body", 1)
        val mine = base.copy()
        val theirs = base.copy(title = "theirs")

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(theirs, retryValue(merged))
    }

    @Test
    fun fields_basePresent_identicalChange_keepsTheirs() {
        val base = Note("base", "body", 1)
        val mine = base.copy(title = "same")
        val theirs = base.copy(title = "same")

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(theirs, retryValue(merged))
    }

    @Test
    fun fields_basePresent_contested_defaultTheirsBias() {
        val base = Note("base", "body", 1)
        val mine = base.copy(title = "mine")
        val theirs = base.copy(title = "theirs")

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(theirs, retryValue(merged))
    }

    @Test
    fun fields_basePresent_contested_mineBias() {
        val base = Note("base", "body", 1)
        val mine = base.copy(title = "mine")
        val theirs = base.copy(title = "theirs")

        val merged = titlePolicy(onBothChanged = MutationConflictBias.MINE)(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals("mine", retryValue(merged).title)
    }

    @Test
    fun fields_basePresent_contested_combineRoutes() {
        val base = Note("base", "body", 1)
        val mine = base.copy(title = "mine")
        val theirs = base.copy(title = "theirs")
        var calls = 0
        val policy = MutationMerges.fields<Note> {
            field(
                get = Note::title,
                set = { value, title -> value.copy(title = title) },
                combine = { _, mineTitle, theirsTitle ->
                    calls += 1
                    "$mineTitle+$theirsTitle"
                },
            )
        }

        val merged = policy(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals("mine+theirs", retryValue(merged).title)
        assertEquals(1, calls)
    }

    @Test
    fun fields_baseAbsent_equalFields_keepTheirs() {
        val mine = Note("same", "mine body", 1)
        val theirs = Note("same", "theirs body", 2)

        val merged = titlePolicy()(
            MutationPresence.Absent,
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(theirs, retryValue(merged))
    }

    @Test
    fun fields_baseAbsent_differingFields_contested() {
        val mine = Note("mine", "mine body", 1)
        val theirs = Note("theirs", "theirs body", 2)

        val merged = titlePolicy(onBothChanged = MutationConflictBias.MINE)(
            MutationPresence.Absent,
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        val value = retryValue(merged)
        assertEquals("mine", value.title)
        assertEquals("theirs body", value.body)
    }

    @Test
    fun fields_registrationOrderAppliesOverSingleCanvas() {
        val base = Note("base", "body", 1)
        val mine = Note("mine", "body", 2)
        val theirs = base.copy()
        var titleSeenBySecondSetter: String? = null
        val policy = MutationMerges.fields<Note> {
            field(Note::title, { value, title -> value.copy(title = title) })
            field(
                Note::count,
                { value, count ->
                    titleSeenBySecondSetter = value.title
                    value.copy(count = count)
                },
            )
        }

        val merged = policy(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        val value = retryValue(merged)
        assertEquals("mine", value.title)
        assertEquals(2, value.count)
        assertEquals("mine", titleSeenBySecondSetter)
    }

    @Test
    fun fields_overlappingLensesLastRegistrationWins() {
        val base = Note("base title", "base body", 1)
        val mine = Note("mine title", "mine body", 1)
        val theirs = base.copy()
        val policy = MutationMerges.fields<Note> {
            field(Note::title, { value, title -> value.copy(title = title) })
            field(Note::body, { value, body -> value.copy(title = body) })
        }

        val merged = policy(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals("mine body", retryValue(merged).title)
    }

    @Test
    fun fields_overlappingLenses_laterKeepTheirsDoesNotRevertEarlierWrite() {
        val base = Note("base title", "base body", 1)
        val mine = Note("mine title", "base body", 1)
        val theirs = base.copy()
        val policy = MutationMerges.fields<Note> {
            field(Note::title, { value, title -> value.copy(title = title) })
            field(Note::body, { value, body -> value.copy(title = body) })
        }

        val merged = policy(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        // The first registration writes "mine title" (theirsField == baseField for title).
        // The second registration's mineField == baseField for body ("base body" == "base body"),
        // so it hits the keep-theirs branch and returns the canvas untouched rather than writing
        // — the first registration's write must survive, not revert to "theirs".
        assertEquals("mine title", retryValue(merged).title)
    }

    @Test
    fun fields_unregisteredFieldResolvesToTheirs() {
        val base = Note("title", "base body", 1)
        val mine = base.copy(body = "mine body")
        val theirs = base.copy(body = "theirs body")

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals("theirs body", retryValue(merged).body)
    }

    @Test
    fun fields_canvasStartsFromTheirs() {
        val base = Note("base title", "base body", 1)
        val mine = base.copy(title = "mine title")
        val theirs = base.copy(body = "theirs body", count = 2)

        val merged = titlePolicy()(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertEquals(Note("mine title", "theirs body", 2), retryValue(merged))
    }

    @Test
    fun fields_arrayFieldComparesByIdentity() {
        val baseArray = arrayOf("value")
        val mineArray = arrayOf("value")
        val base = ArrayNote(baseArray)
        val mine = ArrayNote(mineArray)
        val theirs = ArrayNote(baseArray)
        val policy = MutationMerges.fields<ArrayNote> {
            field(ArrayNote::values, { value, values -> value.copy(values = values) })
        }

        val merged = policy(
            MutationPresence.Present(base),
            MutationPresence.Present(mine),
            MutationPresence.Present(theirs),
        )

        assertSame(mineArray, retryArrayValue(merged).values)
    }

    @Test
    fun fields_combineReceivesWholeBaseValueOrNull() {
        val base = Note("base", "base body", 1)
        val receivedBases = mutableListOf<Note?>()
        val policy = MutationMerges.fields<Note> {
            field(
                get = Note::title,
                set = { value, title -> value.copy(title = title) },
                combine = { receivedBase, mine, _ ->
                    receivedBases += receivedBase
                    mine
                },
            )
        }

        policy(
            MutationPresence.Present(base),
            MutationPresence.Present(base.copy(title = "mine")),
            MutationPresence.Present(base.copy(title = "theirs")),
        )
        policy(
            MutationPresence.Absent,
            MutationPresence.Present(base.copy(title = "created mine")),
            MutationPresence.Present(base.copy(title = "created theirs")),
        )

        assertSame(base, receivedBases[0])
        assertNull(receivedBases[1])
    }

    @Test
    fun fields_emptyBlockThrowsIllegalArgument() {
        assertFailsWith<IllegalArgumentException> {
            MutationMerges.fields<Note> {}
        }
    }

    @Test
    fun fields_escapedBuilderThrowsIllegalState() {
        lateinit var escaped: MutationFieldMergeBuilder<Note>
        MutationMerges.fields<Note> {
            escaped = this
            field(Note::title, { value, title -> value.copy(title = title) })
        }

        assertFailsWith<IllegalStateException> {
            escaped.field(Note::body, { value, body -> value.copy(body = body) })
        }
    }

    @Test
    fun fields_getThrowPropagates() {
        val policy = MutationMerges.fields<Note> {
            field<String>(
                get = { throw IllegalStateException("get failure") },
                set = { value, _ -> value },
            )
        }

        val failure = assertFailsWith<IllegalStateException> {
            policy(
                MutationPresence.Present(Note("base", "body", 1)),
                MutationPresence.Present(Note("mine", "body", 1)),
                MutationPresence.Present(Note("theirs", "body", 1)),
            )
        }

        assertEquals("get failure", failure.message)
    }

    @Test
    fun fields_setThrowPropagates() {
        val base = Note("base", "body", 1)
        val policy = MutationMerges.fields<Note> {
            field(Note::title, { _, _ -> throw IllegalStateException("set failure") })
        }

        val failure = assertFailsWith<IllegalStateException> {
            policy(
                MutationPresence.Present(base),
                MutationPresence.Present(base.copy(title = "mine")),
                MutationPresence.Present(base.copy()),
            )
        }

        assertEquals("set failure", failure.message)
    }

    @Test
    fun fields_combineThrowPropagates() {
        val base = Note("base", "body", 1)
        val policy = MutationMerges.fields<Note> {
            field(
                get = Note::title,
                set = { value, title -> value.copy(title = title) },
                combine = { _, _, _ -> throw IllegalStateException("combine failure") },
            )
        }

        val failure = assertFailsWith<IllegalStateException> {
            policy(
                MutationPresence.Present(base),
                MutationPresence.Present(base.copy(title = "mine")),
                MutationPresence.Present(base.copy(title = "theirs")),
            )
        }

        assertEquals("combine failure", failure.message)
    }

    @Test
    fun fields_mineAbsentTheirsBias_serverWins() {
        val resolution = titlePolicy(
            onMineAbsent = MutationConflictBias.THEIRS,
        )(
            MutationPresence.Present(Note("base", "body", 1)),
            MutationPresence.Absent,
            MutationPresence.Present(Note("theirs", "body", 1)),
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun fields_mineAbsentMineBias_retryAbsent() {
        val mine: MutationPresence<Note> = MutationPresence.Absent
        val resolution = titlePolicy(
            onMineAbsent = MutationConflictBias.MINE,
        )(
            MutationPresence.Present(Note("base", "body", 1)),
            mine,
            MutationPresence.Present(Note("theirs", "body", 1)),
        )

        assertIs<MutationConflictResolution.Retry<Note>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun fields_theirsAbsentTheirsBias_serverWins() {
        val resolution = titlePolicy(
            onTheirsAbsent = MutationConflictBias.THEIRS,
        )(
            MutationPresence.Present(Note("base", "body", 1)),
            MutationPresence.Present(Note("mine", "body", 1)),
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun fields_theirsAbsentMineBias_retryMine() {
        val mine: MutationPresence<Note> =
            MutationPresence.Present(Note("mine", "body", 1))
        val resolution = titlePolicy(
            onTheirsAbsent = MutationConflictBias.MINE,
        )(
            MutationPresence.Present(Note("base", "body", 1)),
            mine,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.Retry<Note>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun fields_bothAbsent_serverWins() {
        val resolution = titlePolicy()(
            MutationPresence.Present(Note("base", "body", 1)),
            MutationPresence.Absent,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    private fun titlePolicy(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onBothChanged: MutationConflictBias = MutationConflictBias.THEIRS,
    ): MutationMergeFunction<Note> =
        MutationMerges.fields(
            onMineAbsent = onMineAbsent,
            onTheirsAbsent = onTheirsAbsent,
            onBothChanged = onBothChanged,
        ) {
            field(Note::title, { value, title -> value.copy(title = title) })
        }

    private fun retryValue(resolution: MutationConflictResolution<Note>): Note {
        val retry = assertIs<MutationConflictResolution.Retry<Note>>(resolution)
        return assertIs<MutationPresence.Present<Note>>(retry.value).value
    }

    private fun retryArrayValue(resolution: MutationConflictResolution<ArrayNote>): ArrayNote {
        val retry = assertIs<MutationConflictResolution.Retry<ArrayNote>>(resolution)
        return assertIs<MutationPresence.Present<ArrayNote>>(retry.value).value
    }

    private data class Note(
        val title: String,
        val body: String,
        val count: Int,
    )

    private data class ArrayNote(
        val values: Array<String>,
    )
}
