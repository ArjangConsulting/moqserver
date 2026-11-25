// swift-tools-version:5.9
import PackageDescription

/// MoqTestSupport, the Swift client for driving a running moqserver from an app's UI or integration
/// tests. It lives at the repository root so an app can depend on it by repository URL; the server
/// itself is a separate package in `server/`, with its own platform floor, and is not part of this
/// one. Sources are in `server/MoqTestSupport`.
let package = Package(
    name: "MoqTestSupport",
    platforms: [
        .iOS(.v13),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "MoqTestSupport", targets: ["MoqTestSupport"])
    ],
    targets: [
        .target(
            name: "MoqTestSupport",
            path: "server/MoqTestSupport/Sources/MoqTestSupport"
        ),
        .testTarget(
            name: "MoqTestSupportTests",
            dependencies: ["MoqTestSupport"],
            path: "server/MoqTestSupport/Tests/MoqTestSupportTests"
        ),
    ]
)
