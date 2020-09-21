package com.dropbox.android.external.fs3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StringPairReadAllPathResolverTest {

    @Test
    fun resolve() {
        assertThat(StringPairReadAllPathResolver.resolve("a" to "b"))
            .isEqualTo("a/b")
    }
}
