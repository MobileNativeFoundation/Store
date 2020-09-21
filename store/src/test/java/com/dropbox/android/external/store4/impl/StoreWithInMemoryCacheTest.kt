package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.Fetcher
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
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class StoreWithInMemoryCacheTest {

    @Test
    fun `store requests can complete when its in-memory cache (with access expiry) is at the maximum size`() {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> "result" })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterAccess(10.minutes)
                    .setMaxSize(1)
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
