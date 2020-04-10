package com.dropbox.android.external.cache4

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ReorderingLinkedHashSetTest {

    private val set = ReorderingLinkedHashSet<String>()

    @Test
    fun `maintains original insertion order when all insertions are unique`() {
        set.add("a")
        set.add("b")

        assertEquals(listOf("a", "b"), set.toList())
    }

    @Test
    fun `re-inserted element is moved to the end`() {
        set.add("a")
        set.add("b")
        set.add("a")

        assertEquals(listOf("b", "a"), set.toList())
    }

    @Test
    fun `calling add(element) returns true if ReorderingLinkedHashSet did not already contain the element`() {
        assertTrue(set.add("a"))
        assertTrue(set.add("b"))
        assertFalse(set.add("a"))
    }
}
