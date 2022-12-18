// swift-tools-version:5.3
import PackageDescription

let remoteKotlinUrl = "https://api.github.com/repos/MobileNativeFoundation/Store/releases/assets/88683716.zip"
let remoteKotlinChecksum = "8608dd438f3bf2bc1d5f326b4cce54f1b87fff8cb984a3e8fdcb58f27a7781c2"
let packageName = "cache"

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