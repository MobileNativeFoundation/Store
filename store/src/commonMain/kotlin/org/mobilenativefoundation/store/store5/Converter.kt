package org.mobilenativefoundation.store.store5

/**
 * Converter is a utility interface that can be used to convert a network or output model to a local model.
 * Network to Model conversion is needed when the network model is different what you are saving in
 * your Source of Truth.
 * Output to Local conversion is needed when you are doing local writes in a MutableStore
 * @param Network the network model type
 * @param Output the output model type
 * @param Local the local model type
 */
interface Converter<Network : Any, Local : Any, Output : Any> {
    fun fromNetworkToLocal(network: Network): Local
    fun fromOutputToLocal(output: Output): Local

    class Builder<Network : Any, Local : Any, Output : Any> {

        lateinit var fromOutputToLocal: ((output: Output) -> Local)
        lateinit var fromNetworkToLocal: ((network: Network) -> Local)

        fun build(): Converter<Network, Local, Output> =
            RealConverter(fromOutputToLocal, fromNetworkToLocal)

        fun fromOutputToLocal(converter: (output: Output) -> Local): Builder<Network, Local, Output> {
            fromOutputToLocal = converter
            return this
        }

        fun fromNetworkToLocal(converter: (network: Network) -> Local): Builder<Network, Local, Output> {
            fromNetworkToLocal = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Local : Any, Output : Any>(
    private val fromOutputToLocal: ((output: Output) -> Local),
    private val fromNetworkToLocal: ((network: Network) -> Local),
) : Converter<Network, Local, Output> {
    override fun fromNetworkToLocal(network: Network): Local =
        fromNetworkToLocal.invoke(network)

    override fun fromOutputToLocal(output: Output): Local =
        fromOutputToLocal.invoke(output)
}
