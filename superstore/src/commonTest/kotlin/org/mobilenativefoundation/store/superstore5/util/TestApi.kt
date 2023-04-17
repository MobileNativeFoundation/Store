package org.mobilenativefoundation.store.superstore5.util

import org.mobilenativefoundation.store.superstore5.util.fake.Page

interface TestApi {
    suspend fun fetch(key: String, fail: Boolean = false, ttl: Long? = null): Page?
}
