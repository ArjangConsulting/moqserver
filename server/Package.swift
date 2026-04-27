// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "moqserver",
    platforms: [
        .macOS(.v12)
    ],
    dependencies: [
        .package(url: "https://github.com/vapor/vapor.git", from: "4.121.4"),
        .package(url: "https://github.com/mattpolzin/OpenAPIKit.git", from: "5.3.0"),
        .package(url: "https://github.com/jpsim/Yams.git", from: "6.2.1"),
        .package(url: "https://github.com/apple/swift-argument-parser.git", from: "1.7.1"),
    ],
    targets: [
        // MARK: - MoqCore
        // Framework-agnostic domain types, protocols, and validation logic.
        // Zero external dependencies.
        .target(
            name: "MoqCore",
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

        // MARK: - MoqParsing
        // OpenAPI/HAR parsers and spec loading.
        .target(
            name: "MoqParsing",
            dependencies: [
                .target(name: "MoqCore"),
                .product(name: "OpenAPIKit", package: "OpenAPIKit"),
                .product(name: "OpenAPIKitCompat", package: "OpenAPIKit"),
                .product(name: "OpenAPIKit30", package: "OpenAPIKit"),
                .product(name: "Yams", package: "Yams"),
            ],
            path: "Sources/MoqParsing"
        ),

        // MARK: - MoqRuntime
        // Vapor routing, handlers, and app bootstrap.
        .target(
            name: "MoqRuntime",
            dependencies: [
                .target(name: "MoqCore"),
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
                .target(name: "MoqParsing"),
                .target(name: "MoqRuntime"),
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ],
            path: "Sources/MoqCLI"
        ),

        // MARK: - Run
        .executableTarget(
            name: "Run",
            dependencies: [
                .target(name: "MoqCLI"),
            ],
            path: "Sources/Run"
        ),

        // MARK: - Tests
        .testTarget(
            name: "MoqCoreTests",
            dependencies: [
                .target(name: "MoqCore"),
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
                .copy("Fixtures"),
            ]
        ),
        .testTarget(
            name: "MoqParsingTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqParsing"),
            ],
            path: "Tests/MoqParsingTests",
            resources: [
                .copy("Fixtures"),
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
                .copy("Fixtures"),
            ]
        ),
        .testTarget(
            name: "MoqIntegrationTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqParsing"),
                .target(name: "MoqRuntime"),
                .product(name: "VaporTesting", package: "vapor"),
                .product(name: "XCTVapor", package: "vapor"),
            ],
            path: "Tests/MoqIntegrationTests",
            resources: [
                .copy("Fixtures"),
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
