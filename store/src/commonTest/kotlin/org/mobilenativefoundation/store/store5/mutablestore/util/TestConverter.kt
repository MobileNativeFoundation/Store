package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.store5.Converter

@Suppress("UNCHECKED_CAST")
class TestConverter<Network : Any, Local : Any, Output : Any>(
    private val defaultNetworkToLocalConverter: ((Network) -> Local)? = null,
    private val defaultOutputToLocalConverter: ((Output) -> Local)? = null,
) : Converter<Network, Local, Output> {
    private val networkToLocalMap: HashMap<Network, Local> = HashMap()
    private val outputToLocalMap: HashMap<Output, Local> = HashMap()

    fun wheneverNetwork(
        network: Network,
        block: () -> Local,
    ) {
        networkToLocalMap[network] = block()
    }

    fun wheneverOutput(
        output: Output,
        block: () -> Local,
    ) {
        outputToLocalMap[output] = block()
    }

    override fun fromNetworkToLocal(network: Network): Local {
        return networkToLocalMap[network] ?: defaultNetworkToLocalConverter?.invoke(network) ?: network as Local
    }

    override fun fromOutputToLocal(output: Output): Local {
        return outputToLocalMap[output] ?: defaultOutputToLocalConverter?.invoke(output) ?: output as Local
    }
}
