//package com.nytimes.android.external.store3.pipeline
//
//import kotlinx.coroutines.*
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.channelFlow
//import kotlinx.coroutines.flow.flow
//import kotlin.coroutines.CoroutineContext
//
//
//
//
//class NewerPersister<Key, Input, Output>(
//        private val fetcher: PipelineStore<Key, Output>,
//        private val reader: (Key) -> Flow<Output?>,
//        private val writer: suspend (Key, Output) -> Unit,
//        private val delete: (suspend (Key) -> Unit)? = null
//) : PipelineStore<Key, Output>, StateProcessor<State<Output>>(State()) {
//    val scope = CoroutineScope(SupervisorJob())
//
//
//
//    data class State<T>(val networkValue: Async<StoreResponse<T>> = Uninitialized,
//                        val diskValue: Async<T?> = Uninitialized,
//                        val fetchingNetwork: Boolean = false,
//                        val writingValue: Boolean = false,
//                        val error:StoreResponse.Error<T>? = null,
//                        val loading:StoreResponse.Loading<T>? = null,
//                        val lastSource:String):MvRxState
//
//    override fun stream(request: StoreRequest<Key>): Flow<Any?> = channelFlow {
//
//        if(request.refresh){
//            setState { this.copy(fetchingNetwork = true) }
//        }
//
//        //TODO: only do for first stream request
//        reader(request.key).execute {
//            this.copy(diskValue = it, lastSource = "Disk")
//        }
//
//
//        selectSubscribe(State<Output>::fetchingNetwork, scope) {
//            fetcher.stream(request).execute {
//                this.copy(networkValue = it, lastSource = "Network", fetchingNetwork = false, writingValue = true)
//            }
//        }
//
//        selectSubscribe(State<Output>::networkValue, scope) {
//            launch {
//                writer(request.key, it.invoke()!!.requireData())
//                setState { this.copy(writingValue = false) }
//            }
//        }
//
//        selectSubscribe(State<Output>::diskValue, scope){
//            withState {
//                //we got a new disk value, do we want to check any other state like last error or last source of emission?
//                send(it.diskValue)
//            }
//        }
//
//        selectSubscribe(State<Output>::error, scope){
//            withState {
//                send(it.error)
//            }
//        }
//
//    }
//
//
//    override suspend fun clear(key: Key) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//}
//
