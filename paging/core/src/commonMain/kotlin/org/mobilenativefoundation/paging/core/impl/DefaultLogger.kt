package org.mobilenativefoundation.paging.core.impl

import co.touchlab.kermit.CommonWriter
import org.mobilenativefoundation.paging.core.Logger

class DefaultLogger : Logger {
    override fun log(message: String) {
        logger.d(
            """
            
            $message
            
            """.trimIndent(),
        )
    }

    private val logger =
        co.touchlab.kermit.Logger.apply {
            setLogWriters(listOf(CommonWriter()))
            setTag("org.mobilenativefoundation.paging")
        }
}