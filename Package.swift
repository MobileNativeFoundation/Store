// swift-tools-version:5.3
import PackageDescription

let remoteKotlinUrl = "https://api.github.com/repos/MobileNativeFoundation/Store/releases/assets/86973057.zip"
let remoteKotlinChecksum = "a221d100e71f5e146dc878251f8f87b7d1528cc40b761a98c4dea4b71226a8e4"
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