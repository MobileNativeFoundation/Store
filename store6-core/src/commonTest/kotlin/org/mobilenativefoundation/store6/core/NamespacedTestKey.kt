package org.mobilenativefoundation.store6.core

class NamespacedTestKey(
    ns: String,
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(ns)

    override fun canonicalId(): String = id
}
