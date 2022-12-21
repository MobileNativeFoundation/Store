package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.internal.definition.Converter

interface StoreConverter<NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any> {
    fun fromNetworkRepresentationToCommonRepresentation(networkRepresentation: NetworkRepresentation): CommonRepresentation?
    fun fromCommonRepresentationToSourceOfTruthRepresentation(commonRepresentation: CommonRepresentation): SourceOfTruthRepresentation?
    fun fromSourceOfTruthRepresentationToCommonRepresentation(sourceOfTruthRepresentation: SourceOfTruthRepresentation): CommonRepresentation?

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
    override fun fromNetworkRepresentationToCommonRepresentation(networkRepresentation: NetworkRepresentation): CommonRepresentation? =
        fromNetworkToCommon?.invoke(networkRepresentation)

    override fun fromCommonRepresentationToSourceOfTruthRepresentation(commonRepresentation: CommonRepresentation): SourceOfTruthRepresentation? =
        fromCommonToSourceOfTruth?.invoke(commonRepresentation)

    override fun fromSourceOfTruthRepresentationToCommonRepresentation(sourceOfTruthRepresentation: SourceOfTruthRepresentation): CommonRepresentation? =
        fromSourceOfTruthToCommon?.invoke(sourceOfTruthRepresentation)
}
