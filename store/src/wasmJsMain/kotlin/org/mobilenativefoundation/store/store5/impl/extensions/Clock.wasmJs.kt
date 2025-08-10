package org.mobilenativefoundation.store.store5.impl.extensions

@JsFun("() => Date.now()")
private external fun dateNow(): Double

internal actual fun currentTimeMillis(): Long = dateNow().toLong()
