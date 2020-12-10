package com.dropbox.kmp.external.store4.testutil.coroutines
import kotlinx.coroutines.runBlocking as nativeRunBlocking

actual fun runBlocking(block: suspend () -> Unit) = nativeRunBlocking {
    block()
}