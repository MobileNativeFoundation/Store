import XCTest
import Store6Kotlin
@testable import Store6

final class StoreStatesTests: XCTestCase {

    func testStates_reachDataWithTypedValue() async throws {
        let client = StoreClient<StoreNamespaceKey, NSString>.make { key in
            "v-\(key.id)" as NSString
        }
        var seen: [StoreState<NSString>] = []
        for await state in client.states(for: StoreNamespaceKey(namespace: "s", id: "1")) {
            seen.append(state)
            if case .data = state { break }
            if case .error = state { break }
        }
        guard case let .data(value, _, _, _, _) = seen.last else {
            return XCTFail("expected data, got \(String(describing: seen.last))")
        }
        XCTAssertEqual(value as String, "v-1")
    }

    func testStates_fetchFailure_arrivesAsErrorStateNotThrow() async {
        struct Backend: Error {}
        let client = StoreClient<StoreNamespaceKey, NSString>.make { _ in throw Backend() }
        var last: StoreState<NSString>?
        for await state in client.states(for: StoreNamespaceKey(namespace: "s", id: "2"), freshness: .mustBeFresh) {
            last = state
            if case .error = state { break }
        }
        guard case .error(.fetch, _) = last else {
            return XCTFail("expected .error(.fetch), got \(String(describing: last))")
        }
    }

    func testStates_cancellation_stopsIteration() async throws {
        let client = StoreClient<StoreNamespaceKey, NSString>.make { key in
            "v-\(key.id)" as NSString
        }
        let task = Task {
            var count = 0
            for await _ in client.states(for: StoreNamespaceKey(namespace: "s", id: "3")) {
                count += 1
            }
            return count
        }
        try await Task.sleep(nanoseconds: 200_000_000)
        task.cancel()
        let count = await task.value
        XCTAssertGreaterThanOrEqual(count, 1)
    }
}
