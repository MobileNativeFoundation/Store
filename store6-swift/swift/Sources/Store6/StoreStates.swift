import Store6Kotlin

/// The stream of states for one key.
///
/// Never throws: read failures arrive as `.error` elements. Iteration ends when the stream
/// completes or the consuming task is cancelled.
public struct StoreStates<Value>: AsyncSequence {
    public typealias Element = StoreState<Value>

    let flow: SkieSwiftFlow<StoreStateBridge>

    public func makeAsyncIterator() -> AsyncIterator {
        AsyncIterator(inner: flow.makeAsyncIterator())
    }

    public struct AsyncIterator: AsyncIteratorProtocol {
        var inner: SkieSwiftFlow<StoreStateBridge>.AsyncIterator

        public mutating func next() async -> StoreState<Value>? {
            guard let bridge = await inner.next() else { return nil }
            return StoreState(bridge: bridge)
        }
    }
}

extension StoreClient {
    public func states(
        for key: Key,
        freshness: StoreFreshness = .cachedOrFetch
    ) -> StoreStates<Value> {
        StoreStates(
            flow: storeStates(
                store: kotlinStore,
                key: key.kotlinKey,
                freshness: freshness.kotlin
            )
        )
    }
}
