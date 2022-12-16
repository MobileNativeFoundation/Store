package org.mobilenativefoundation.store.store5.definition

typealias PostRequest<Key, CommonRepresentation, NetworkWriteResponse> = suspend (key: Key, input: CommonRepresentation) -> NetworkWriteResponse
