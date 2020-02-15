package com.dropbox.android.external.fs3

import com.dropbox.android.external.store4.legacy.BarCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BarCodeReadAllPathResolverTest {

    @Test
    fun resolve() {
        assertThat(BarCodeReadAllPathResolver.resolve(BarCode("a", "b")))
            .isEqualTo("a/b")
    }
}
