package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.get
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.time.ExperimentalTime
import kotlin.time.minutes

@FlowPreview
@ExperimentalTime
@ExperimentalStdlibApi
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ConcurrentStoreRequestTest {

    @Test
    fun `concurrent store requests can complete when loaded data exceeds maximum in-memory cache size`() {
        val store = StoreBuilder
            .fromNonFlow { _: Int -> "result" }
            .cachePolicy(
                MemoryPolicy
                    .builder()
                    .setExpireAfterAccess(10.minutes)
                    .setMemorySize(1)
                    .build()
            )
            .build()

        runBlocking {
            store.get(0)
            store.get(0)
            store.get(1)
            store.get(2)
        }
    }
}
