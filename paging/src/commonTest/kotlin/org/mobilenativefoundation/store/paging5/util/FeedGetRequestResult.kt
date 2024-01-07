package org.mobilenativefoundation.store.paging5.util

sealed class FeedGetRequestResult {

    data class Data(val data: PostData.Feed) : FeedGetRequestResult()
    sealed class Error : FeedGetRequestResult() {
        data class Message(val error: String) : Error()
        data class Exception(val error: kotlin.Exception) : Error()
    }
}
