package org.mobilenativefoundation.store.paging5.util

sealed class PostGetRequestResult {
    data class Data(val data: PostData.Post) : PostGetRequestResult()

    sealed class Error : PostGetRequestResult() {
        data class Message(val error: String) : Error()

        data class Exception(val error: kotlin.Exception) : Error()
    }
}
