import XCTest
import Store6Kotlin
@testable import Store6

final class StoreFailureTests: XCTestCase {

    func testFetchError_lifts() {
        let kotlin = StoreResults.shared.fetchError(message: "socket closed", cause: nil)
        XCTAssertEqual(StoreFailure(kotlin: kotlin), .fetch(message: "socket closed"))
    }

    func testMissingError_carriesKeyCoordinates() {
        let key = SwiftStoreKey(namespace: "users", id: "9")
        let kotlin = StoreResults.shared.missing(key: key, message: "no such row")
        XCTAssertEqual(
            StoreFailure(kotlin: kotlin),
            .missing(message: "no such row", namespace: "users", id: "9")
        )
    }

    func testAllSixTaxonomyCases_lift() {
        let key = SwiftStoreKey(namespace: "n", id: "1")
        let cases: [(StoreError, StoreFailure)] = [
            (StoreResults.shared.conflict(serverMeta: nil, message: "m1"), .conflict(message: "m1")),
            (StoreResults.shared.conversionError(message: "m2", cause: nil), .conversion(message: "m2")),
            (StoreResults.shared.fetchError(message: "m3", cause: nil), .fetch(message: "m3")),
            (StoreResults.shared.freshnessUnsatisfiable(message: "m4"), .freshnessUnsatisfiable(message: "m4")),
            (StoreResults.shared.missing(key: key, message: "m5"), .missing(message: "m5", namespace: "n", id: "1")),
            (StoreResults.shared.persistenceError(message: "m6", cause: nil), .persistence(message: "m6")),
        ]
        for (kotlin, expected) in cases {
            XCTAssertEqual(StoreFailure(kotlin: kotlin), expected)
        }
    }

    func testExtract_fromThrownStoreException() {
        let kotlinError = StoreResults.shared.fetchError(message: "boom", cause: nil)
        let exception = StoreResults.shared.exception(error: kotlinError, cause: nil)
        let nsError = exception.asError()
        XCTAssertEqual(StoreFailure.extract(from: nsError), .fetch(message: "boom"))
    }

    func testExtract_fromForeignError_isNil() {
        struct Foreign: Error {}
        XCTAssertNil(StoreFailure.extract(from: Foreign()))
    }
}
