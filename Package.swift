// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Store6",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "Store6", targets: ["Store6"]),
        .library(name: "Store6SwiftUI", targets: ["Store6SwiftUI"]),
    ],
    targets: [
        // Built by `./gradlew :store6-swift:assembleStore6KotlinDebugXCFramework`.
        // Switched to a url/checksum release asset when the facade ships in a release.
        .binaryTarget(
            name: "Store6Kotlin",
            path: "store6-swift/build/XCFrameworks/debug/Store6Kotlin.xcframework"
        ),
        .target(
            name: "Store6",
            dependencies: ["Store6Kotlin"],
            path: "store6-swift/swift/Sources/Store6"
        ),
        .target(
            name: "Store6SwiftUI",
            dependencies: ["Store6"],
            path: "store6-swift/swift/Sources/Store6SwiftUI"
        ),
        .testTarget(
            name: "Store6Tests",
            dependencies: ["Store6"],
            path: "store6-swift/swift/Tests/Store6Tests"
        ),
        .testTarget(
            name: "Store6SwiftUITests",
            dependencies: ["Store6SwiftUI"],
            path: "store6-swift/swift/Tests/Store6SwiftUITests"
        ),
    ]
)
