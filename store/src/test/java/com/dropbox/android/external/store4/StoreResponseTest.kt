package com.dropbox.android.external.store4

import com.dropbox.android.external.store4.ResponseOrigin.Fetcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoreResponseTest {

    @Test(expected = NullPointerException::class)
    fun requireData() {
        assertThat(StoreResponse.Data("Foo", Fetcher).requireData())
            .isEqualTo("Foo")
        //should throw
        StoreResponse.Loading<Any>(Fetcher).requireData()
    }

    @Test(expected = RuntimeException::class)
    fun throwIfError() {
        StoreResponse.Error<Any>(RuntimeException(), Fetcher).throwIfError()
    }

    @Test()
    fun errorOrNull() {
        assertThat(StoreResponse.Error<Any>(RuntimeException(), Fetcher).errorOrNull()).isInstanceOf(RuntimeException::class.java)
        assertThat( StoreResponse.Loading<Any>(Fetcher).errorOrNull()).isNull()
    }

    @Test(expected = IllegalStateException::class)
    fun swapType() {
        StoreResponse.Data("Foo", Fetcher).swapType<String>()
    }
}
