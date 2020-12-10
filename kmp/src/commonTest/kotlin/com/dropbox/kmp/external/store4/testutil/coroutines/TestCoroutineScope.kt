/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package com.dropbox.kmp.external.store4.testutil.coroutines

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@ExperimentalCoroutinesApi
interface TestCoroutineScope: CoroutineScope, UncaughtExceptionCaptor, DelayController {
    override fun cleanupTestCoroutines()
}

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
fun TestCoroutineScope(context: CoroutineContext = EmptyCoroutineContext): TestCoroutineScope {
    var safeContext = context
    if (context[ContinuationInterceptor] == null) safeContext += TestCoroutineDispatcher()
    if (context[CoroutineExceptionHandler] == null) safeContext += TestCoroutineExceptionHandler()
    return TestCoroutineScopeImpl(safeContext)
}

interface UncaughtExceptionCaptor {
    /**
     * List of uncaught coroutine exceptions.
     *
     * The returned list is a copy of the currently caught exceptions.
     * During [cleanupTestCoroutines] the first element of this list is rethrown if it is not empty.
     */
    val uncaughtExceptions: List<Throwable>

    /**
     * Call after the test completes to ensure that there were no uncaught exceptions.
     *
     * The first exception in uncaughtExceptions is rethrown. All other exceptions are
     * printed using [Throwable.printStackTrace].
     *
     * @throws Throwable the first uncaught exception, if there are any uncaught exceptions.
     */
    fun cleanupTestCoroutines()
}

private inline val CoroutineContext.uncaughtExceptionCaptor: UncaughtExceptionCaptor
    get() {
        val handler = this[CoroutineExceptionHandler]
        return handler as? UncaughtExceptionCaptor ?: throw IllegalArgumentException(
                "TestCoroutineScope requires a UncaughtExceptionCaptor such as " +
                        "TestCoroutineExceptionHandler as the CoroutineExceptionHandler"
        )
    }

@ExperimentalCoroutinesApi
private inline val CoroutineContext.delayController: DelayController
    get() {
        val handler = this[ContinuationInterceptor]
        return handler as? DelayController ?: throw IllegalArgumentException(
                "TestCoroutineScope requires a DelayController such as TestCoroutineDispatcher as " +
                        "the ContinuationInterceptor (Dispatcher)"
        )
    }

@ExperimentalCoroutinesApi
private class TestCoroutineScopeImpl (
        override val coroutineContext: CoroutineContext
):
        TestCoroutineScope,
        UncaughtExceptionCaptor by coroutineContext.uncaughtExceptionCaptor,
        DelayController by coroutineContext.delayController
{
    override fun cleanupTestCoroutines() {
        coroutineContext.uncaughtExceptionCaptor.cleanupTestCoroutines()
        coroutineContext.delayController.cleanupTestCoroutines()
    }
}