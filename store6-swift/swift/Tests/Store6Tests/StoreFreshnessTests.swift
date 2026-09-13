import XCTest
import Store6Kotlin
@testable import Store6

final class StoreFreshnessTests: XCTestCase {

    func testSimpleCases_mapToKotlinSingletons() {
        XCTAssertTrue(StoreFreshness.cachedOrFetch.kotlin is FreshnessCachedOrFetch)
        XCTAssertTrue(StoreFreshness.localOnly.kotlin is FreshnessLocalOnly)
        XCTAssertTrue(StoreFreshness.mustBeFresh.kotlin is FreshnessMustBeFresh)
        XCTAssertTrue(StoreFreshness.staleIfError.kotlin is FreshnessStaleIfError)
    }

    func testMaxAge_carriesMilliseconds() {
        let kotlin = StoreFreshness.maxAge(notOlderThanMilliseconds: 60_000).kotlin
        XCTAssertTrue(kotlin is FreshnessMaxAge)
    }
}
