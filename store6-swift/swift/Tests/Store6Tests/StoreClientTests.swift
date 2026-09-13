import XCTest
import Store6Kotlin
@testable import Store6

final class StoreClientTests: XCTestCase {

    private struct UserKey: StoreKeyRepresentable {
        let id: String
        var storeNamespace: String { "users" }
        var storeID: String { id }
        static func reconstruct(namespace: String, id: String) -> UserKey { UserKey(id: id) }
    }

    func testMake_getReturnsTypedValue() async throws {
        let client = StoreClient<UserKey, NSString>.make { key in
            "user-\(key.id)" as NSString
        }
        let value = try await client.get(UserKey(id: "1"))
        XCTAssertEqual(value as String, "user-1")
    }

    func testMake_swiftFetcherThrow_surfacesAsFetchFailure() async {
        struct Backend: Error {}
        let client = StoreClient<UserKey, NSString>.make { _ in throw Backend() }
        do {
            _ = try await client.get(UserKey(id: "2"), freshness: .mustBeFresh)
            XCTFail("expected a throw")
        } catch let failure as StoreFailure {
            guard case .fetch = failure else {
                return XCTFail("expected .fetch, got \(failure)")
            }
        } catch {
            XCTFail("expected StoreFailure, got \(error)")
        }
    }

    func testGet_wrongValueType_throwsConversionFailure() async {
        let client = StoreClient<UserKey, NSNumber>.make(wrappingUntyped: { _ in "str" as NSString })
        do {
            _ = try await client.get(UserKey(id: "3"))
            XCTFail("expected a throw")
        } catch let failure as StoreFailure {
            guard case .conversion = failure else {
                return XCTFail("expected .conversion, got \(failure)")
            }
        } catch {
            XCTFail("expected StoreFailure, got \(error)")
        }
    }

    func testInvalidateAndClear_complete() async throws {
        let client = StoreClient<UserKey, NSString>.make { key in "u-\(key.id)" as NSString }
        _ = try await client.get(UserKey(id: "4"))
        try await client.invalidate(UserKey(id: "4"))
        try await client.clear(UserKey(id: "4"))
        try await client.clearNamespace("users")
        try await client.invalidateAll()
        client.close()
    }
}
