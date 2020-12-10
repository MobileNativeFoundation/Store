package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.Fetcher
import com.dropbox.kmp.external.store4.MemoryPolicy
import com.dropbox.kmp.external.store4.StoreBuilder
import com.dropbox.kmp.external.store4.get
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.minutes

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class StoreWithInMemoryCacheTest {

    // Todo - Implement multiplatform cache
//    @Test
//    fun `store requests can complete when its in-memory cache (with access expiry) is at the maximum size`() {
//        val store = StoreBuilder
//            .from(Fetcher.of { _: Int -> "result" })
//            .cachePolicy(
//                MemoryPolicy
//                    .builder<Any, Any>()
//                    .setExpireAfterAccess(10.minutes)
//                    .setMaxSize(1)
//                    .build()
//            )
//            .build()
//
//        runBlocking {
//            store.get(0)
//            store.get(0)
//            store.get(1)
//            store.get(2)
//        }
//    }
}
