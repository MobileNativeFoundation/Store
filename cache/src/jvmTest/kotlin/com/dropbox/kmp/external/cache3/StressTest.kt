package com.dropbox.kmp.external.cache3

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import kotlin.test.Test

@StressCTest
@ModelCheckingCTest
@Param(name = "key", gen = IntGen::class, conf = "1:5")
class StressTest {
    private val s = cacheBuilder<Int, Int> { }

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) {
        s.put(key, value)
    }

    @Operation
    operator fun get(@Param(name = "key") key: Int) {
        s.getIfPresent(key)
    }

    @Operation
    fun invalidate(@Param(name = "key") key: Int) {
        s.invalidate(key)
    }

    @Test
    fun runTest() {
        LinChecker.check(
            this::class.java, StressOptions()
                .iterations(10)
                .threads(3)
                .logLevel(LoggingLevel.WARN)
        )
    }
}
