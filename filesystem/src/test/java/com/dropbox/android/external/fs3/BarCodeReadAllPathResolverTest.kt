package com.dropbox.android.external.fs3

import com.dropbox.android.external.store4.legacy.BarCode
import org.junit.Test

import org.junit.Assert.*
import com.google.common.truth.Truth.assertThat

class BarCodeReadAllPathResolverTest {

    @Test
    fun resolve() {
       assertThat (BarCodeReadAllPathResolver.resolve(BarCode("a", "b")))
           .isEqualTo("a/b")
    }
}