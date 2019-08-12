package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.Parser
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.util.KeyParser
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun <V1, V2, K> Store4Builder<V1, K>.parser(parser: (V1) -> V2): Store4Builder<V2, K> = parser(object : Parser<V1, V2> {
    override suspend fun apply(raw: V1) = parser(raw)
})

fun <V1, V2, K> Store4Builder<V1, K>.parser(parser: Parser<V1, V2>): Store4Builder<V2, K> =
        parser(object : KeyParser<K, V1, V2> {
            override suspend fun apply(key: K, raw: V1) = parser.apply(raw)
        })

fun <V1, V2, K> Store4Builder<V1, K>.parser(parser: KeyParser<K, V1, V2>): Store4Builder<V2, K> =
        Store4Builder(ParserStore(wrappedStore, parser))

internal class ParserStore<V1, V2, K>(
        private val wrappedStore: Store<V1, K>,
        private val parser: KeyParser<K, V1, V2>
) : Store<V2, K> {
    override suspend fun get(key: K): V2 = parser.apply(key, wrappedStore.get(key))

    override suspend fun fresh(key: K): V2 = parser.apply(key, wrappedStore.fresh(key))

    @FlowPreview
    override fun stream(): Flow<Pair<K, V2>> = wrappedStore.stream().map { (key, value) -> key to parser.apply(key, value) }

    override suspend fun clear(key: K) {
        wrappedStore.clear(key)
    }
}