package com.dropbox.android.external.store4

import com.dropbox.android.external.store4.ResponseOrigin.Fetcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException

class StoreResponseTest {

    @Test(expected = NullPointerException::class)
    fun requireData() {
        assertThat(StoreResponse.Data("Foo", Fetcher).requireData())
            .isEqualTo("Foo")
        // should throw
        StoreResponse.Loading(Fetcher).requireData()
    }

    @Test(expected = IOException::class)
    fun throwIfErrorException() {
        StoreResponse.Error.Exception(IOException(), Fetcher).throwIfError()
    }

    @Test(expected = RuntimeException::class)
    fun throwIfErrorMessage() {
        StoreResponse.Error.Message("test error", Fetcher).throwIfError()
    }

    @Test()
    fun errorMessageOrNull() {
        assertThat(
            StoreResponse.Error.Exception(
                IOException(),
                Fetcher
            ).errorMessageOrNull()
        ).contains(IOException::class.java.toString())
        assertThat(
            StoreResponse.Error.Message(
                "test error message",
                Fetcher
            ).errorMessageOrNull()
        ).isEqualTo("test error message")
        assertThat(StoreResponse.Loading(Fetcher).errorMessageOrNull()).isNull()
    }

    @Test(expected = RuntimeException::class)
    fun swapType() {
        StoreResponse.Data("Foo", Fetcher).swapType<String>()
    }
}
