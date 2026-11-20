package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.freshOrNull
import org.mobilenativefoundation.store.store5.impl.extensions.freshOrResult
import org.mobilenativefoundation.store.store5.impl.extensions.getOrNull
import org.mobilenativefoundation.store.store5.impl.extensions.getOrResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeExtensionsDeviceTest {

    @Test
    fun safeGetAndFreshExtensionsRunOnAndroidDevice() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> {
                if (it == "fail") throw IllegalStateException("Device error") else "android_$it"
            },
        ).build()

        val successResult = store.getOrResult("success")
        assertTrue(successResult.isSuccess)
        assertEquals("android_success", successResult.getOrNull())

        val failureResult = store.getOrResult("fail")
        assertTrue(failureResult.isFailure)
        assertTrue(failureResult.exceptionOrNull() is IllegalStateException)

        val nullValue = store.getOrNull("fail")
        assertNull(nullValue)

        val freshVal = store.freshOrNull("freshKey")
        assertEquals("android_freshKey", freshVal)
    }
}
