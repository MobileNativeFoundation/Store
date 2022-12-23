package org.mobilenativefoundation.store.store5

interface Converter<Network : Any, Output : Any, Local : Any> {
    fun fromNetworkToOutput(network: Network): Output?
    fun fromOutputToLocal(common: Output): Local?
    fun fromLocalToOutput(sourceOfTruth: Local): Output?

    class Builder<Network : Any, Output : Any, Local : Any> {

        private var fromOutputToLocal: ((input: Output) -> Local)? = null
        private var fromNetworkToOutput: ((input: Network) -> Output)? = null
        private var fromLocalToOutput: ((input: Local) -> Output)? = null

        fun build(): Converter<Network, Output, Local> =
            RealConverter(fromOutputToLocal, fromNetworkToOutput, fromLocalToOutput)

        fun fromOutputToLocal(converter: (input: Output) -> Local): Builder<Network, Output, Local> {
            fromOutputToLocal = converter
            return this
        }

        fun fromLocalToOutput(converter: (input: Local) -> Output): Builder<Network, Output, Local> {
            fromLocalToOutput = converter
            return this
        }

        fun fromNetworkToOutput(converter: (input: Network) -> Output): Builder<Network, Output, Local> {
            fromNetworkToOutput = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Output : Any, Local : Any>(
    private val fromOutputToLocal: ((input: Output) -> Local)?,
    private val fromNetworkToOutput: ((input: Network) -> Output)?,
    private val fromLocalToOutput: ((input: Local) -> Output)?,
) : Converter<Network, Output, Local> {
    override fun fromNetworkToOutput(network: Network): Output? =
        fromNetworkToOutput?.invoke(network)

    override fun fromOutputToLocal(common: Output): Local? =
        fromOutputToLocal?.invoke(common)

    override fun fromLocalToOutput(sourceOfTruth: Local): Output? =
        fromLocalToOutput?.invoke(sourceOfTruth)
}
