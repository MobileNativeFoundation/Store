package org.mobilenativefoundation.store.store5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoreReadResponseTests {
    @Test
    fun requireData() {
        assertEquals("Foo", StoreReadResponse.Data("Foo", StoreReadResponseOrigin.Fetcher()).requireData())

        // should throw
        assertFailsWith<NullPointerException> {
            StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()).requireData()
        }
    }

    @Test
    fun throwIfErrorException() {
        assertFailsWith<Exception> {
            StoreReadResponse.Error.Exception(Exception(), StoreReadResponseOrigin.Fetcher()).throwIfError()
        }
    }

    @Test
    fun throwIfErrorMessage() {
        assertFailsWith<RuntimeException> {
            StoreReadResponse.Error.Message("test error", StoreReadResponseOrigin.Fetcher()).throwIfError()
        }
    }

    @Test()
    fun errorMessageOrNull() {
        assertFailsWith<Exception>(message = Exception::class.toString()) {
            StoreReadResponse.Error.Exception(Exception(), StoreReadResponseOrigin.Fetcher()).throwIfError()
        }

        assertFailsWith<Exception>(message = "test error message") {
            StoreReadResponse.Error.Message("test error message", StoreReadResponseOrigin.Fetcher()).throwIfError()
        }

        assertNull(StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()).errorMessageOrNull())
    }

    @Test
    fun swapType() {
        assertFailsWith<RuntimeException> {
            StoreReadResponse.Data("Foo", StoreReadResponseOrigin.Fetcher()).swapType<String>()
        }
    }
}
