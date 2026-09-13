import XCTest
import Store6Kotlin
@testable import Store6

final class SmokeTests: XCTestCase {

    func testSwiftFetcherStore_getRoundTrips() async throws {
        let store = SwiftInteropKt.swiftStore { key, completion in
            completion("value-for-\(key.canonicalId())", nil)
        }
        let key = SwiftStoreKey(namespace: "smoke", id: "1")
        let value = try await store.get(key: key, freshness: FreshnessCachedOrFetch.shared)
        XCTAssertEqual(value as? String, "value-for-1")
    }
}
