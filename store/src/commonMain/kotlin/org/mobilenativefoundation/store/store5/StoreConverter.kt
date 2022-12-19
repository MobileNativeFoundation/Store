package org.mobilenativefoundation.store.store5

interface StoreConverter<NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any> {
    fun toCommonRepresentation(networkRepresentation: NetworkRepresentation): CommonRepresentation?
    fun toSourceOfTruthRepresentation(commonRepresentation: CommonRepresentation): SourceOfTruthRepresentation?
    fun toCommonRepresentation(sourceOfTruthRepresentation: SourceOfTruthRepresentation): CommonRepresentation?

    class Builder<NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any> {

        private var fromCommonToSourceOfTruth: Converter<CommonRepresentation, SourceOfTruthRepresentation>? = null
        private var fromNetworkToCommon: Converter<NetworkRepresentation, CommonRepresentation>? = null
        private var fromSourceOfTruthToCommon: Converter<SourceOfTruthRepresentation, CommonRepresentation>? = null

        fun build(): StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> =
            RealStoreConverter(fromCommonToSourceOfTruth, fromNetworkToCommon, fromSourceOfTruthToCommon)

        fun fromCommonToSourceOfTruth(converter: Converter<CommonRepresentation, SourceOfTruthRepresentation>): Builder<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
            fromCommonToSourceOfTruth = converter
            return this
        }

        fun fromSourceOfTruthToCommon(converter: Converter<SourceOfTruthRepresentation, CommonRepresentation>): Builder<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
            fromSourceOfTruthToCommon = converter
            return this
        }

        fun fromNetworkToCommon(converter: Converter<NetworkRepresentation, CommonRepresentation>): Builder<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
            fromNetworkToCommon = converter
            return this
        }

    }
}

private class RealStoreConverter<NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any>(
    private val fromCommonToSourceOfTruth: Converter<CommonRepresentation, SourceOfTruthRepresentation>?,
    private val fromNetworkToCommon: Converter<NetworkRepresentation, CommonRepresentation>?,
    private val fromSourceOfTruthToCommon: Converter<SourceOfTruthRepresentation, CommonRepresentation>?
) : StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
    override fun toCommonRepresentation(networkRepresentation: NetworkRepresentation): CommonRepresentation? =
        fromNetworkToCommon?.invoke(networkRepresentation)

    override fun toSourceOfTruthRepresentation(commonRepresentation: CommonRepresentation): SourceOfTruthRepresentation? =
        fromCommonToSourceOfTruth?.invoke(commonRepresentation)

    override fun toCommonRepresentation(sourceOfTruthRepresentation: SourceOfTruthRepresentation): CommonRepresentation? =
        fromSourceOfTruthToCommon?.invoke(sourceOfTruthRepresentation)
}