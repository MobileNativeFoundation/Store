package org.mobilenativefoundation.store.store5.impl.extensions

@Suppress("UnsafeCastFromDynamic")
internal actual fun currentTimeMillis(): Long =
    kotlin.js.Date
        .now()
        .toLong()
