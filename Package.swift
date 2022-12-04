// swift-tools-version:5.3
import PackageDescription

let remoteKotlinUrl = "https://api.github.com/repos/MobileNativeFoundation/Store/releases/assets/86971405.zip"
let remoteKotlinChecksum = "b8ae36fc3fab5aade98fd0950ee505f812f36c6b924f7ed8454bfae7cd2b31bc"
let packageName = "store"

let package = Package(
    name: packageName,
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: packageName,
            targets: [packageName]
        ),
    ],
    targets: [
        .binaryTarget(
            name: packageName,
            url: remoteKotlinUrl,
            checksum: remoteKotlinChecksum
        )
        ,
    ]
)