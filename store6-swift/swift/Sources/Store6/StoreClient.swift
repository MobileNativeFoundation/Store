import Foundation
import Store6Kotlin

/// Typed Swift handle to a Store.
///
/// `Key` is any Swift key conforming to `StoreKeyRepresentable`; `Value` is the served value type.
/// Values cross the Kotlin bridge as objects, so `Value` is typically a class type (`NSString`,
/// `NSNumber`, or an application model class shared through Kotlin).
public struct StoreClient<Key: StoreKeyRepresentable, Value> {

    let kotlinStore: any Store

    /// Wraps a store built in shared Kotlin code.
    public init(wrapping store: any Store) {
        self.kotlinStore = store
    }

    /// Builds a store whose fetcher is the given Swift async closure.
    ///
    /// The closure runs inside a `Task` started by the Kotlin fetch; cancelling the Kotlin fetch
    /// does not cancel a closure invocation already in flight.
    public static func make(
        fetch: @escaping @Sendable (Key) async throws -> Value
    ) -> StoreClient<Key, Value> {
        make(wrappingUntyped: { key in try await fetch(key) as Any })
    }

    /// Untyped variant used by `make` and by tests that need a deliberately mistyped fetcher.
    static func make(
        wrappingUntyped fetch: @escaping @Sendable (Key) async throws -> Any
    ) -> StoreClient<Key, Value> {
        let store = SwiftInteropKt.swiftStore { kotlinKey, completion in
            let key = Key.reconstruct(namespace: kotlinKey.namespace.value, id: kotlinKey.canonicalId())
            Task {
                do {
                    completion(try await fetch(key), nil)
                } catch {
                    completion(nil, String(describing: error))
                }
            }
        }
        return StoreClient(wrapping: store)
    }

    public func get(
        _ key: Key,
        freshness: StoreFreshness = .cachedOrFetch
    ) async throws -> Value {
        let anyValue: Any
        do {
            // The global storeGet wrapper carries the @Throws declaration; calling the protocol
            // member directly would make a failing read fatal at the bridge instead of catchable.
            anyValue = try await storeGet(store: kotlinStore, key: key.kotlinKey, freshness: freshness.kotlin)
        } catch {
            throw Self.mapped(error)
        }
        guard let value = anyValue as? Value else {
            throw StoreFailure.conversion(
                message: "Store value of type \(type(of: anyValue)) is not \(Value.self)"
            )
        }
        return value
    }

    public func invalidate(_ key: Key) async throws {
        try await kotlinStore.invalidate(key: key.kotlinKey)
    }

    public func invalidateAll() async throws {
        try await kotlinStore.invalidateAll()
    }

    public func invalidateNamespace(_ namespace: String) async throws {
        try await kotlinStore.invalidateNamespace(namespace: StoreNamespace(value: namespace))
    }

    public func clear(_ key: Key) async throws {
        try await kotlinStore.clear(key: key.kotlinKey)
    }

    public func clearAll() async throws {
        try await kotlinStore.clearAll()
    }

    public func clearNamespace(_ namespace: String) async throws {
        try await kotlinStore.clearNamespace(namespace: StoreNamespace(value: namespace))
    }

    public func close() {
        kotlinStore.close()
    }

    private static func mapped(_ error: any Error) -> any Error {
        if error is CancellationError { return error }
        if let failure = StoreFailure.extract(from: error) { return failure }
        return error
    }
}
