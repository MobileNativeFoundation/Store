import XCTest
import Store6
import Store6SwiftUI

@MainActor
final class StoreQueryTests: XCTestCase {

    private func makeClient(value: String = "v") -> StoreClient<StoreNamespaceKey, NSString> {
        StoreClient<StoreNamespaceKey, NSString>.make { key in "\(value)-\(key.id)" as NSString }
    }

    func testStart_publishesLoadingThenData() async throws {
        let query = StoreQuery(client: makeClient(), key: StoreNamespaceKey(namespace: "q", id: "1"))
        guard case .loading = query.state else {
            return XCTFail("initial state must be loading")
        }
        query.start()
        try await waitUntil { if case .data = query.state { return true }; return false }
        guard case let .data(value, _, _, _, _) = query.state else {
            return XCTFail("expected data, got \(query.state)")
        }
        XCTAssertEqual(value as String, "v-1")
        query.stop()
    }

    func testRekey_switchesTheStreamedKey() async throws {
        let query = StoreQuery(client: makeClient(), key: StoreNamespaceKey(namespace: "q", id: "1"))
        query.start()
        try await waitUntil { if case .data = query.state { return true }; return false }
        query.rekey(to: StoreNamespaceKey(namespace: "q", id: "2"))
        try await waitUntil {
            if case let .data(value, _, _, _, _) = query.state { return (value as String) == "v-2" }
            return false
        }
        query.stop()
    }

    func testStop_haltsPublishing() async throws {
        let query = StoreQuery(client: makeClient(), key: StoreNamespaceKey(namespace: "q", id: "3"))
        query.start()
        try await waitUntil { if case .data = query.state { return true }; return false }
        query.stop()
        // After stop the task is cancelled; no crash and state stays a valid case.
        if case .loading = query.state { XCTFail("state regressed to loading after stop") }
    }

    /// Condition-based waiting: polls the MainActor state with a deadline, never a bare sleep.
    private func waitUntil(
        deadline: TimeInterval = 5.0,
        _ condition: @MainActor () -> Bool
    ) async throws {
        let start = Date()
        while !condition() {
            if Date().timeIntervalSince(start) > deadline {
                return XCTFail("condition not met within \(deadline)s")
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }
}
