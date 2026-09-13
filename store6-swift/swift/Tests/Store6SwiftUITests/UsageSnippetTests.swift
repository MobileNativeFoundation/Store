#if canImport(SwiftUI)
import SwiftUI
import XCTest
import Store6
import Store6SwiftUI

/// The README's SwiftUI example, kept compiling by CI. Behavioral coverage lives in
/// StoreQueryTests; this file only pins the public spelling the documentation promises.
@MainActor
final class UsageSnippetTests: XCTestCase {

    struct UserView: View {
        @StateObject var query: StoreQuery<StoreNamespaceKey, NSString>

        var body: some View {
            switch query.state {
            case .loading:
                ProgressView()
            case .data(let name, _, _, let isStale, _):
                Text(name as String).opacity(isStale ? 0.5 : 1.0)
            case .revalidated:
                ProgressView()
            case .error(let failure, _):
                Text("Failed: \(String(describing: failure))")
            }
        }
    }

    func testSnippetCompilesAndInstantiates() {
        let client = StoreClient<StoreNamespaceKey, NSString>.make { key in
            "user-\(key.id)" as NSString
        }
        let view = UserView(
            query: StoreQuery(client: client, key: StoreNamespaceKey(namespace: "users", id: "1"))
        )
        XCTAssertNotNil(view.body)
    }
}
#endif
