package org.mobilenativefoundation.store.store5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoreResponseTests {

    @Test
    fun requireData() {
        assertEquals("Foo", StoreResponse.Data("Foo", ResponseOrigin.Fetcher).requireData())

        // should throw
        assertFailsWith<NullPointerException> {
            StoreResponse.Loading(ResponseOrigin.Fetcher).requireData()
        }
    }

    @Test
    fun throwIfErrorException() {
        assertFailsWith<Exception> {
            StoreResponse.Error.Exception(Exception(), ResponseOrigin.Fetcher).throwIfError()
        }
    }

    @Test
    fun throwIfErrorMessage() {
        assertFailsWith<RuntimeException> {
            StoreResponse.Error.Message("test error", ResponseOrigin.Fetcher).throwIfError()
        }
    }

    @Test()
    fun errorMessageOrNull() {
        assertFailsWith<Exception>(message = Exception::class.toString()) {
            StoreResponse.Error.Exception(Exception(), ResponseOrigin.Fetcher).throwIfError()
        }

        assertFailsWith<Exception>(message = "test error message") {
            StoreResponse.Error.Message("test error message", ResponseOrigin.Fetcher).throwIfError()
        }

        assertNull(StoreResponse.Loading(ResponseOrigin.Fetcher).errorMessageOrNull())
    }

    @Test
    fun swapType() {
        assertFailsWith<RuntimeException> {
            StoreResponse.Data("Foo", ResponseOrigin.Fetcher).swapType<String>()
        }
    }
}
