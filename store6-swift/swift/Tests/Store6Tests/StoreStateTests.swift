import XCTest
import Store6Kotlin
@testable import Store6

final class StoreStateTests: XCTestCase {

    // Bridge instances are produced by the Kotlin stream in production; tests reach them the same
    // way a caller would — through a real single-emission store stream (the path the Kotlin bridge
    // tests proved). StoreStateBridge's constructor is internal to the Kotlin module by design, so
    // there is deliberately no test-only bridge constructor.

    func testLoadingLifts() async throws {
        let states = try await collectStates(fetchValue: "v")
        guard case .loading = states.first else {
            return XCTFail("first state was \(String(describing: states.first))")
        }
    }

    func testDataLifts_typedValueAndMillis() async throws {
        let states = try await collectStates(fetchValue: "typed")
        guard case let .data(value, origin, ageMilliseconds, isStale, refreshing) = states.last else {
            return XCTFail("last state was \(String(describing: states.last))")
        }
        XCTAssertEqual(value as String, "typed")
        XCTAssertEqual(origin, .fetcher)
        XCTAssertGreaterThanOrEqual(ageMilliseconds, 0)
        XCTAssertFalse(isStale)
        XCTAssertFalse(refreshing)
    }

    func testDataWithWrongType_liftsToConversionError() async throws {
        // Value type Int, fetcher returns a String: the typed lift must refuse.
        let store = SwiftInteropKt.swiftStore { _, completion in completion("not-an-int", nil) }
        let key = StoreNamespaceKey(namespace: "s", id: "1")
        let flow = storeStates(
            store: store, key: key.kotlinKey, freshness: StoreFreshness.cachedOrFetch.kotlin
        )
        var lifted: [StoreState<NSNumber>] = []
        for await bridge in flow {
            lifted.append(StoreState<NSNumber>(bridge: bridge))
            if case .data = lifted.last! { break }
            if case .error = lifted.last! { break }
        }
        guard case .error(.conversion, _) = lifted.last else {
            return XCTFail("expected conversion error, got \(String(describing: lifted.last))")
        }
    }

    /// Streams a fresh single-value store until the first Data (or Error) state and lifts each.
    private func collectStates(fetchValue: String) async throws -> [StoreState<NSString>] {
        let store = SwiftInteropKt.swiftStore { _, completion in completion(fetchValue, nil) }
        let key = StoreNamespaceKey(namespace: "s", id: "k")
        let flow = storeStates(
            store: store, key: key.kotlinKey, freshness: StoreFreshness.cachedOrFetch.kotlin
        )
        var lifted: [StoreState<NSString>] = []
        for await bridge in flow {
            lifted.append(StoreState<NSString>(bridge: bridge))
            if case .data = lifted.last! { break }
            if case .error = lifted.last! { break }
        }
        return lifted
    }
}
