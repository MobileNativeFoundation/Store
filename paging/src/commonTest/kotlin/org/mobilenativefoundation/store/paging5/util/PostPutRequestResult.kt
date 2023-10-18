package org.mobilenativefoundation.store.paging5.util

sealed class PostPutRequestResult {

    data class Data(val data: PostData.Post) : PostPutRequestResult()
    sealed class Error : PostPutRequestResult() {
        data class Message(val error: String) : Error()
        data class Exception(val error: kotlin.Exception) : Error()
    }
}