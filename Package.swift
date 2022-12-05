// swift-tools-version:5.3
import PackageDescription

let remoteKotlinUrl = "https://api.github.com/repos/MobileNativeFoundation/Store/releases/assets/87002894.zip"
let remoteKotlinChecksum = "49ebc3e8aea90b485a014311018f52a59eca5aebdb697311d3e9cdf75eca3a98"
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