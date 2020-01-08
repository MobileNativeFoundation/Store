package com.dropbox.android.external.cache4

/**
 * A custom [LinkedHashSet] that updates the insertion order when an element is re-inserted,
 * i.e. an inserted element will always be placed at the end
 * regardless of whether the element already exists.
 */
internal class ReorderingLinkedHashSet<E> : LinkedHashSet<E>() {

    override fun add(element: E): Boolean {
        val exists = remove(element)
        super.add(element)
        // respect the contract "true if this set did not already contain the specified element"
        return !exists
    }
}
