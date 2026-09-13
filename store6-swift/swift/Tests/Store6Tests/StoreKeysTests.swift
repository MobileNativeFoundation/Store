import XCTest
import Store6Kotlin
@testable import Store6

final class StoreKeysTests: XCTestCase {

    func testNamespaceKey_mapsToKotlinKey() {
        let key = StoreNamespaceKey(namespace: "users", id: "42")
        let kotlin = key.kotlinKey
        XCTAssertEqual(kotlin.canonicalId(), "42")
        XCTAssertEqual(kotlin.namespace.value, "users")
    }

    func testCustomKeyType_mapsToKotlinKey() {
        struct UserKey: StoreKeyRepresentable {
            let id: String
            var storeNamespace: String { "users" }
            var storeID: String { id }
        }
        let kotlin = UserKey(id: "7").kotlinKey
        XCTAssertEqual(kotlin.canonicalId(), "7")
        XCTAssertEqual(kotlin.namespace.value, "users")
    }
}
