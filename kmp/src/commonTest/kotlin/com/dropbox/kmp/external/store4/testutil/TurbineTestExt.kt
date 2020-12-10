package com.dropbox.kmp.external.store4.testutil

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalCoroutinesApi
@ExperimentalTime
suspend fun <T> Flow<T>.emitsExactlyAndCompletes(vararg expected: T, duration: Duration = 1.seconds) = this.test(timeout = duration) {
    for(i in expected){
        assertEquals(i, expectItem())
    }
    expectComplete()
}

@ExperimentalCoroutinesApi
@ExperimentalTime
suspend fun <T> Flow<T>.emitsExactly(vararg expected: T, duration: Duration = 1.seconds) = this.test(timeout = duration) {
    for(i in expected){
        assertEquals(i, expectItem())
    }
    expectNoEvents()
}