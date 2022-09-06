package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.fresh

import junit.framework.Assert.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RefreshTest {

    @Test
    fun refreshWithErrorWithSourceOfTruth() = runBlocking {
        val fetcher = Fetcher.ofResult<Unit, String> {
            FetcherResult.Error.Message("Error")
        }
        val sourceOfTruth = InMemorySourceOfTruth()
        val store = StoreBuilder.from(fetcher, sourceOfTruth).build()

        val result = mutableListOf<StoreResponse<String>>()
        val job = launch {
            store.stream(StoreRequest.cached(Unit, refresh = true))
                .toList(result)
        }

        delay(1000)
        runCatching {
            store.fresh(Unit)
        }

        delay(1000)
        job.cancel()

        val expected = listOf<StoreResponse<String>>(
            StoreResponse.Loading(origin = ResponseOrigin.Fetcher),
            StoreResponse.Error.Message(message = "Error", origin = ResponseOrigin.Fetcher),
            StoreResponse.Error.Message(message = "Error", origin = ResponseOrigin.Fetcher)
        )
        assertEquals(expected, result)
    }
    @Test
    fun refreshWithErrorNoSourceOfTruth() = runBlocking {
        val fetcher = Fetcher.ofResult<Unit, String> {
            FetcherResult.Error.Message("Error")
        }
        val store = StoreBuilder.from(fetcher).build()

        val result = mutableListOf<StoreResponse<String>>()
        val job = launch {
            store.stream(StoreRequest.cached(Unit, refresh = true))
                .toList(result)
        }

        delay(1000)
        runCatching {
            store.fresh(Unit)
        }

        delay(1000)
        job.cancel()

        val expected = listOf<StoreResponse<String>>(
            StoreResponse.Loading(origin = ResponseOrigin.Fetcher),
            StoreResponse.Error.Message(message = "Error", origin = ResponseOrigin.Fetcher),
            StoreResponse.Error.Message(message = "Error", origin = ResponseOrigin.Fetcher)
        )
        assertEquals(expected, result)
    }
}

class InMemorySourceOfTruth : SourceOfTruth<Unit, String, String> {
    private val stateFlow = MutableStateFlow<String?>(null)

    override suspend fun delete(key: Unit) {
        stateFlow.value = null
    }

    override suspend fun deleteAll() {
        stateFlow.value = null
    }

    override fun reader(key: Unit): Flow<String?> {
        return stateFlow
    }

    override suspend fun write(key: Unit, value: String) {
        stateFlow.value = value
    }
}
