package com.dropbox.android.external.cache4.testutil

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A [TestRule] that supports retrying failing tests which have been annotated with [ConcurrencyTest].
 */
class ConcurrencyTestRule : TestRule {
    private var error: Throwable? = null
    private var attemptIndex = 0

    override fun apply(
        base: Statement,
        description: Description
    ): Statement {
        val annotation: ConcurrencyTest =
            description.getAnnotation(ConcurrencyTest::class.java) ?: return base
        val times: Int = annotation.retries
        require(times > 0) { "Number of retires must be greater than 1." }
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                while (attemptIndex < times) {
                    try {
                        base.evaluate()
                        return
                    } catch (t: Throwable) {
                        error = t
                        attemptIndex++
                    }
                }
                throw error!!
            }
        }
    }
}

/**
 * Retry failing unit tests for the number of times specified.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
annotation class ConcurrencyTest(
    val retries: Int = 3
)
