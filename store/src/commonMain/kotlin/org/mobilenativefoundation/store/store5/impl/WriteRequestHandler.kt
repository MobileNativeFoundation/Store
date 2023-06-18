package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse

/**
 * This internal interface defines a handler for writing requests to [RealMutableStore].
 * @param Key The type of key used in [RealStore].
 * @param Output The type of output used in [RealStore].
 */
internal interface WriteRequestHandler<Key : Any, Output : Any> {

    /**
     * This function is invoked when a write request needs to be processed.
     * @param request The [StoreWriteRequest] to be processed.
     * @return The [StoreWriteResponse] which could be a success or an error.
     * @throws Exception if any error occurs during the processing of the request.
     */
    suspend operator fun <Response : Any> invoke(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse
}
