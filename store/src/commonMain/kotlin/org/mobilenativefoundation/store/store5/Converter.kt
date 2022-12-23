package org.mobilenativefoundation.store.store5

interface Converter<Network : Any, Output : Any, Local : Any> {
    fun fromNetworkToOutput(network: Network): Output?
    fun fromOutputToLocal(common: Output): Local?
    fun fromLocalToOutput(sourceOfTruth: Local): Output?

    class Builder<Network : Any, Output : Any, Local : Any> {

        private var fromOutputToLocal: ((value: Output) -> Local)? = null
        private var fromNetworkToOutput: ((value: Network) -> Output)? = null
        private var fromLocalToOutput: ((value: Local) -> Output)? = null

        fun build(): Converter<Network, Output, Local> =
            RealConverter(fromOutputToLocal, fromNetworkToOutput, fromLocalToOutput)

        fun fromOutputToLocal(converter: (value: Output) -> Local): Builder<Network, Output, Local> {
            fromOutputToLocal = converter
            return this
        }

        fun fromLocalToOutput(converter: (value: Local) -> Output): Builder<Network, Output, Local> {
            fromLocalToOutput = converter
            return this
        }

        fun fromNetworkToOutput(converter: (value: Network) -> Output): Builder<Network, Output, Local> {
            fromNetworkToOutput = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Output : Any, Local : Any>(
    private val fromOutputToLocal: ((value: Output) -> Local)?,
    private val fromNetworkToOutput: ((value: Network) -> Output)?,
    private val fromLocalToOutput: ((value: Local) -> Output)?,
) : Converter<Network, Output, Local> {
    override fun fromNetworkToOutput(network: Network): Output? =
        fromNetworkToOutput?.invoke(network)

    override fun fromOutputToLocal(common: Output): Local? =
        fromOutputToLocal?.invoke(common)

    override fun fromLocalToOutput(sourceOfTruth: Local): Output? =
        fromLocalToOutput?.invoke(sourceOfTruth)
}
