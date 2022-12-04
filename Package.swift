// swift-tools-version:5.3
import PackageDescription

let remoteKotlinUrl = "https://api.github.com/repos/MobileNativeFoundation/Store/releases/assets/86969096.zip"
let remoteKotlinChecksum = "c5b62cc0c4551add66f83c4c68d24ed100394522622dd0ceb104969259d5b947"
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