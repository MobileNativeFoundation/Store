package org.mobilenativefoundation.store.store5.market.definition

typealias PostRequest<Key, CommonRepresentation, NetworkWriteResponse> = suspend (key: Key, input: CommonRepresentation) -> NetworkWriteResponse
