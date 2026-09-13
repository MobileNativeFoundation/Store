package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotSame

class FetcherDispatcherTest {
    @Test
    fun fetcherSynchronousWork_runsOffTheCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        lateinit var fetcherThread: Thread
        val store = store<TestKey, String> {
            fetcher {
                fetcherThread = Thread.currentThread()
                "value"
            }
        }

        try {
            store.get(TestKey("1"))

            assertNotSame(callerThread, fetcherThread)
        } finally {
            store.close()
        }
    }
}
