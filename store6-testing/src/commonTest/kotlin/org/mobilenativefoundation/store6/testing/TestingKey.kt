package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class TestingKey(ns: String, private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(ns)
    override fun canonicalId(): String = id
}
