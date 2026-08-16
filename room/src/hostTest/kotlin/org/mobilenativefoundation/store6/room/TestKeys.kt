package org.mobilenativefoundation.store6.room

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

internal class RoomKitKey(
    override val namespace: StoreNamespace,
    private val id: String,
) : StoreKey {
    override fun canonicalId(): String = id
}
