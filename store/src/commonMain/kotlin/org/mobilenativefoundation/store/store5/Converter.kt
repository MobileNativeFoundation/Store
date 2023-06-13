package org.mobilenativefoundation.store.store5

interface Converter<Network : Any, Output : Any, Local : Any> {
    fun fromNetworkToLocal(network: Network): Local
    fun fromOutputToLocal(output: Output): Local

    class Builder<Network : Any, Output : Any, Local : Any> {

        lateinit var fromOutputToLocal: ((output: Output) -> Local)
        lateinit var fromNetworkToLocal: ((network: Network) -> Local)

        fun build(): Converter<Network, Output, Local> =
            RealConverter(fromOutputToLocal, fromNetworkToLocal)

        fun fromOutputToLocal(converter: (output: Output) -> Local): Builder<Network, Output, Local> {
            fromOutputToLocal = converter
            return this
        }

        fun fromNetworkToOutput(converter: (network: Network) -> Local): Builder<Network, Output, Local> {
            fromNetworkToLocal = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Output : Any, Local : Any>(
    private val fromOutputToLocal: ((output: Output) -> Local),
    private val fromNetworkToLocal: ((network: Network) -> Local),
) : Converter<Network, Output, Local> {
    override fun fromNetworkToLocal(network: Network): Local =
        fromNetworkToLocal.invoke(network)

    override fun fromOutputToLocal(output: Output): Local =
        fromOutputToLocal.invoke(output)
}
