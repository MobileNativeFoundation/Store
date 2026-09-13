package org.mobilenativefoundation.store6.benchmarks

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

internal val BENCH_NAMESPACE = StoreNamespace("bench")

internal class BenchKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = BENCH_NAMESPACE

    override fun canonicalId(): String = id
}
