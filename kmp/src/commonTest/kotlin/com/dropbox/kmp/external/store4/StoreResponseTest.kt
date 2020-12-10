package com.dropbox.kmp.external.store4

import com.dropbox.kmp.external.store4.ResponseOrigin.Fetcher
import kotlin.test.*

class StoreResponseTest {

    @Test
    fun requireData() {
        assertFailsWith<NullPointerException> {
            assertEquals("Foo", StoreResponse.Data("Foo", Fetcher).requireData())

            // should throw
            StoreResponse.Loading(Fetcher).requireData()
        }
    }

    @Test
    fun throwIfErrorException() {
        assertFailsWith<IllegalStateException> {
            StoreResponse.Error.Exception(IllegalStateException(), Fetcher).throwIfError()
        }
    }

    @Test
    fun throwIfErrorMessage() {
        assertFailsWith<RuntimeException> {
            StoreResponse.Error.Message("test error", Fetcher).throwIfError()
        }
    }

    // todo - revisit exception name in errorMessageOrNull
    // -is .simpleName the proper implementation here?
    @Test
    fun errorMessageOrNull() {
        assertTrue {
            StoreResponse.Error.Exception(
                    IllegalStateException(),
                    Fetcher
            ).errorMessageOrNull()?.contains(IllegalStateException::class.simpleName!!) ?: false
        }

        assertEquals(
            "test error message",
                StoreResponse.Error.Message(
                        "test error message",
                        Fetcher
                ).errorMessageOrNull())
        assertNull(StoreResponse.Loading(Fetcher).errorMessageOrNull())
    }

    @Test
    fun swapType() {
        assertFailsWith<RuntimeException> {
            StoreResponse.Data("Foo", Fetcher).swapType<String>()
        }
    }
}
