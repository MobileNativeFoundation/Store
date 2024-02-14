package org.mobilenativefoundation.store.paging5.impl

import co.touchlab.kermit.CommonWriter
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.Logger
import co.touchlab.kermit.Logger as Kermit

@ExperimentalStoreApi
class DefaultLogger : Logger {
    override fun d(message: Any) {
        logger.d(
            """
            
            $message
            
            """.trimIndent()
        )
    }

    private val logger = Kermit.apply {
        setLogWriters(listOf(CommonWriter()))
        setTag("Store")
    }
}
