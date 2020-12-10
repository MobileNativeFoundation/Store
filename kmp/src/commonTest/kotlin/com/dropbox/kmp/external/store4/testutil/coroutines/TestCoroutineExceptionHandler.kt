package com.dropbox.kmp.external.store4.testutil.coroutines

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
class TestCoroutineExceptionHandler :
        AbstractCoroutineContextElement(CoroutineExceptionHandler), UncaughtExceptionCaptor, CoroutineExceptionHandler
{
    private val _exceptions = atomic(listOf<Throwable>())

    /** @suppress **/
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        _exceptions.lazySet(_exceptions.value + exception)
    }

    /** @suppress **/
    override val uncaughtExceptions: List<Throwable>
        get() = _exceptions.value

    /** @suppress **/
    override fun cleanupTestCoroutines() {
            val exception = _exceptions.value.firstOrNull() ?: return
            // log the rest
            _exceptions.value.drop(1).forEach { it.printStackTrace() }
            throw exception
        }
}