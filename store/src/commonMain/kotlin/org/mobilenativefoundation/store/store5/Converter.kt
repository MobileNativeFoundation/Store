package org.mobilenativefoundation.store.store5

interface Converter<Network : Any, Common : Any, SOT : Any> {
    fun fromNetworkToCommon(network: Network): Common?
    fun fromCommonToSOT(common: Common): SOT?
    fun fromSOTToCommon(sourceOfTruth: SOT): Common?

    class Builder<Network : Any, Common : Any, SOT : Any> {

        private var fromCommonToSOT: ((input: Common) -> SOT)? = null
        private var fromNetworkToCommon: ((input: Network) -> Common)? = null
        private var fromSOTToCommon: ((input: SOT) -> Common)? = null

        fun build(): Converter<Network, Common, SOT> =
            RealConverter(fromCommonToSOT, fromNetworkToCommon, fromSOTToCommon)

        fun fromCommonToSOT(converter: (input: Common) -> SOT): Builder<Network, Common, SOT> {
            fromCommonToSOT = converter
            return this
        }

        fun fromSOTToCommon(converter: (input: SOT) -> Common): Builder<Network, Common, SOT> {
            fromSOTToCommon = converter
            return this
        }

        fun fromNetworkToCommon(converter: (input: Network) -> Common): Builder<Network, Common, SOT> {
            fromNetworkToCommon = converter
            return this
        }
    }
}

private class RealConverter<Network : Any, Common : Any, SOT : Any>(
    private val fromCommonToSOT: ((input: Common) -> SOT)?,
    private val fromNetworkToCommon: ((input: Network) -> Common)?,
    private val fromSOTToCommon: ((input: SOT) -> Common)?,
) : Converter<Network, Common, SOT> {
    override fun fromNetworkToCommon(network: Network): Common? =
        fromNetworkToCommon?.invoke(network)

    override fun fromCommonToSOT(common: Common): SOT? =
        fromCommonToSOT?.invoke(common)

    override fun fromSOTToCommon(sourceOfTruth: SOT): Common? =
        fromSOTToCommon?.invoke(sourceOfTruth)
}
