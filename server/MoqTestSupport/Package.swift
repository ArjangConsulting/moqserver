// swift-tools-version:5.9
import PackageDescription

/// Test-support package for apps whose UI/integration tests run against a `moqserver` instance.
/// Deliberately a separate package (own `Package.swift`, own platform floor) rather than a target
/// inside `../Package.swift` — that package targets macOS/Linux server binaries and never links
/// XCTest outside its own `.testTarget`s; this one is meant to be added as an SPM dependency to an
/// iOS (or macOS) app's own test target, the same way a consuming app already depends on XCTest.
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
            path: "Sources/MoqTestSupport"
        ),
        .testTarget(
            name: "MoqTestSupportTests",
            dependencies: ["MoqTestSupport"],
            path: "Tests/MoqTestSupportTests"
        ),
    ]
)
