// swift-tools-version:6.1
import PackageDescription

let package = Package(
    name: "moqserver",
    platforms: [
        .macOS(.v12)
    ],
    products: [
        .executable(name: "moqserver", targets: ["Run"])
    ],
    dependencies: [
        .package(url: "https://github.com/vapor/vapor.git", from: "4.122.0"),
        .package(url: "https://github.com/jpsim/Yams.git", from: "6.2.2"),
        .package(url: "https://github.com/apple/swift-argument-parser.git", from: "1.8.2"),
        .package(url: "https://github.com/apple/swift-log.git", from: "1.15.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.101.3"),
    ],
    targets: [
        // MARK: - MoqCore
        // Framework-agnostic domain types, protocols, and validation logic.
        // Depends only on swift-log (already in the graph via Vapor).
        .target(
            name: "MoqCore",
            dependencies: [
                .product(name: "Logging", package: "swift-log")
            ],
            path: "Sources/MoqCore"
        ),

        // MARK: - MoqFormat
        // .moqproj project format: loading, writing, validation, and conversion.
        .target(
            name: "MoqFormat",
            dependencies: [
                .target(name: "MoqCore"),
                .product(name: "Yams", package: "Yams"),
            ],
            path: "Sources/MoqFormat"
        ),

        // MARK: - MoqRuntime
        // Vapor routing, handlers, and app bootstrap.
        .target(
            name: "MoqRuntime",
            dependencies: [
                .target(name: "MoqCore"),
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "Vapor", package: "vapor"),
                .product(name: "Yams", package: "Yams"),
            ],
            path: "Sources/MoqRuntime"
        ),

        // MARK: - MoqCLI
        // CLI command definitions and composition root.
        .target(
            name: "MoqCLI",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqRuntime"),
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ],
            path: "Sources/MoqCLI"
        ),

        // MARK: - Run
        .executableTarget(
            name: "Run",
            dependencies: [
                .target(name: "MoqCLI")
            ],
            path: "Sources/Run"
        ),

        // MARK: - Tests
        .testTarget(
            name: "MoqCoreTests",
            dependencies: [
                .target(name: "MoqCore")
            ],
            path: "Tests/MoqCoreTests"
        ),
        .testTarget(
            name: "MoqFormatTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
            ],
            path: "Tests/MoqFormatTests",
            resources: [
                .copy("Fixtures")
            ]
        ),
        .testTarget(
            name: "MoqRuntimeTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqRuntime"),
                .product(name: "VaporTesting", package: "vapor"),
                .product(name: "XCTVapor", package: "vapor"),
            ],
            path: "Tests/MoqRuntimeTests",
            resources: [
                .copy("Fixtures")
            ]
        ),
        .testTarget(
            name: "MoqIntegrationTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqRuntime"),
                .product(name: "VaporTesting", package: "vapor"),
                .product(name: "XCTVapor", package: "vapor"),
            ],
            path: "Tests/MoqIntegrationTests",
            resources: [
                .copy("Fixtures")
            ]
        ),
        .testTarget(
            name: "MoqCLITests",
            dependencies: [
                .target(name: "MoqCLI"),
                .target(name: "MoqCore"),
            ],
            path: "Tests/MoqCLITests"
        ),
    ]
)
