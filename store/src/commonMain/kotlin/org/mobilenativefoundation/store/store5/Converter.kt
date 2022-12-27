package org.mobilenativefoundation.store.store5

interface Converter<Network : Any, Output : Any, Local : Any> {
    fun fromNetworkToOutput(network: Network): Output?
    fun fromOutputToLocal(output: Output): Local?
    fun fromLocalToOutput(local: Local): Output?

    class Builder<Network : Any, Output : Any, Local : Any> {

        private var fromOutputToLocal: ((output: Output) -> Local)? = null
        private var fromNetworkToOutput: ((network: Network) -> Output)? = null
        private var fromLocalToOutput: ((local: Local) -> Output)? = null

        fun build(): Converter<Network, Output, Local> =
            RealConverter(fromOutputToLocal, fromNetworkToOutput, fromLocalToOutput)

        fun fromOutputToLocal(converter: (output: Output) -> Local): Builder<Network, Output, Local> {
            fromOutputToLocal = converter
            return this
        }

        fun fromLocalToOutput(converter: (local: Local) -> Output): Builder<Network, Output, Local> {
            fromLocalToOutput = converter
            return this
        }

        fun fromNetworkToOutput(converter: (network: Network) -> Output): Builder<Network, Output, Local> {
            fromNetworkToOutput = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Output : Any, Local : Any>(
    private val fromOutputToLocal: ((output: Output) -> Local)?,
    private val fromNetworkToOutput: ((network: Network) -> Output)?,
    private val fromLocalToOutput: ((local: Local) -> Output)?,
) : Converter<Network, Output, Local> {
    override fun fromNetworkToOutput(network: Network): Output? =
        fromNetworkToOutput?.invoke(network)

    override fun fromOutputToLocal(output: Output): Local? =
        fromOutputToLocal?.invoke(output)

    override fun fromLocalToOutput(local: Local): Output? =
        fromLocalToOutput?.invoke(local)
}
