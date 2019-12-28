package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReorderingLinkedHashSetTest {

    private val set = ReorderingLinkedHashSet<String>()

    @Test
    fun `maintains original insertion order when all insertions are unique`() {
        set.add("a")
        set.add("b")

        assertThat(set.toList())
            .isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `re-inserted element is moved to the end`() {
        set.add("a")
        set.add("b")
        set.add("a")

        assertThat(set.toList())
            .isEqualTo(listOf("b", "a"))
    }

    @Test
    fun `calling add(element) returns true if ReorderingLinkedHashSet did not already contain the element`() {
        assertThat(set.add("a"))
            .isTrue()
        assertThat(set.add("b"))
            .isTrue()
        assertThat(set.add("a"))
            .isFalse()
    }
}
