package com.dropbox.kmp.external.store4.testutil.coroutines

import kotlinx.coroutines.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@InternalCoroutinesApi
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
fun runBlockingTest(context: CoroutineContext = EmptyCoroutineContext, testBody: suspend TestCoroutineScope.() -> Unit) {
    val (safeContext, dispatcher) = context.checkArguments()
    val startingJobs = safeContext.activeJobs()
    val scope = TestCoroutineScope(safeContext)
    val deferred = scope.async {
        scope.testBody()
    }
    dispatcher.advanceUntilIdle()
    deferred.getCompletionExceptionOrNull()?.let {
        throw it
    }
    scope.cleanupTestCoroutines()
    val endingJobs = safeContext.activeJobs()
    if ((endingJobs - startingJobs).isNotEmpty()) {
        throw UncompletedCoroutinesError("Test finished with active jobs: $endingJobs")
    }
}


/**
 * Convenience method for calling [runBlockingTest] on an existing [TestCoroutineScope].
 */
// todo: need documentation on how this extension is supposed to be used
@InternalCoroutinesApi
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
fun TestCoroutineScope.runBlockingTest(block: suspend TestCoroutineScope.() -> Unit): Unit =
        runBlockingTest(coroutineContext, block)

/**
 * Convenience method for calling [runBlockingTest] on an existing [TestCoroutineDispatcher].
 */
@InternalCoroutinesApi
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
fun TestCoroutineDispatcher.runBlockingTest(block: suspend TestCoroutineScope.() -> Unit): Unit =
        runBlockingTest(this, block)

private fun CoroutineContext.activeJobs(): Set<Job> {
    return checkNotNull(this[Job]).children.filter { it.isActive }.toSet()
}

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
private fun CoroutineContext.checkArguments(): Pair<CoroutineContext, DelayController> {
    // TODO optimize it
    val dispatcher = get(ContinuationInterceptor).run {
        this?.let { require(this is DelayController) { "Dispatcher must implement DelayController: $this" } }
        this ?: TestCoroutineDispatcher()
    }

    val exceptionHandler =  get(CoroutineExceptionHandler).run {
        this?.let {
            require(this is UncaughtExceptionCaptor) { "coroutineExceptionHandler must implement UncaughtExceptionCaptor: $this" }
        }
        this ?: TestCoroutineExceptionHandler()
    }

    val job = get(Job) ?: SupervisorJob()
    return Pair(this + dispatcher + exceptionHandler + job, dispatcher as DelayController)
}