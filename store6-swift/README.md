# store6-swift

Swift Package Manager facade over the Store6 Kotlin core: typed async reads, a non-throwing
`AsyncSequence` of read states, an exhaustive Swift error taxonomy, and SwiftUI sugar.

## Consuming

The package manifest lives at the repository root. During the pre-release window the binary
target is built locally:

    ./gradlew :store6-swift:assembleStore6KotlinDebugXCFramework
    swift test

Products:

- `Store6` — `StoreClient`, `StoreState`, `StoreFailure`, `StoreFreshness`, key types.
- `Store6SwiftUI` — `StoreQuery`, an `ObservableObject` publishing `StoreState`.

## Reading

    let client = StoreClient<StoreNamespaceKey, NSString>.make { key in
        try await backend.fetchUser(id: key.id) as NSString
    }

    // One-shot, throws StoreFailure on classified failures:
    let user = try await client.get(StoreNamespaceKey(namespace: "users", id: "1"))

    // Streaming — never throws; failures arrive as .error states:
    for await state in client.states(for: StoreNamespaceKey(namespace: "users", id: "1")) {
        switch state {
        case .loading: break
        case .data(let value, let origin, let ageMilliseconds, let isStale, let refreshing): break
        case .revalidated(let ageMilliseconds): break
        case .error(let failure, let servedStale): break
        }
    }

`StoreFailure` has exactly six cases — conflict, conversion, fetch, freshnessUnsatisfiable,
missing, persistence — mirroring the sealed Kotlin `StoreError` taxonomy, which is frozen at 6.0.
Switching over it is exhaustive by design.

Key types conform to `StoreKeyRepresentable` (`storeNamespace` + `storeID`). Stores built with
`make(fetch:)` reconstruct keys from those two coordinates in the fetcher callback; a key type
with additional stored properties must provide `static func reconstruct(namespace:id:)`.

Stores built in shared Kotlin code wrap directly: `StoreClient(wrapping: kotlinStore)`.

## SwiftUI

    import SwiftUI
    import Store6
    import Store6SwiftUI

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

Call `query.start()` from `.onAppear` (or `.task`) and `query.stop()` from `.onDisappear`. This
snippet is the same `UserView` the test suite compiles, minus the XCTest scaffolding — if the two
drift, the test is the truth and the README follows it.

## Boundaries

- Distribution is deferred. Maintenance failures and calls after Store closure can escape the
  Kotlin bridge's declared exception set and terminate the process. Swift `async throws` alone
  does not make those Kotlin exceptions catchable; checked wrappers and process-survival tests
  are required before distribution.
- Apple targets: iOS (device + simulator) and Apple-silicon macOS. tvOS and watchOS are not in
  this package's target set.
- Values cross the Kotlin bridge as objects; value generics are typed on the Swift side and
  checked at the boundary (a mismatch surfaces as a `conversion` failure, never a crash).
- Cancelling a Kotlin-side fetch does not cancel a Swift fetcher closure already in flight.
- The Kotlin framework `Store6Kotlin` is an implementation detail; its generated Swift surface is
  committed and diffed under `store6-swift/api/swift/skie/`.
- Custom Swift or Objective-C conformers to the raw `Overlay` and `StoreWriteHandle` protocols
  must implement the added adoption-aware projection and acknowledgement methods. Kotlin defaults
  do not generate Swift protocol defaults or omitted-argument overloads. Pass nullable evidence
  and adoption arguments explicitly; raw acknowledgement/status exports do not declare typed
  persistence-error conversion.
