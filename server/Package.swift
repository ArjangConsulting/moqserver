// swift-tools-version:6.1
import PackageDescription

let package = Package(
    name: "moqserver",
    platforms: [
        // Raised from .v12 for the MCP Swift SDK, which declares a macOS 13 minimum.
        // (Considered keeping MoqMCP in a separate package so the server binary could stay on
        // macOS 12; given this repo's CI only tests Linux + latest macOS, the version floor
        // costs nothing in practice, so the simpler single-package layout won out.)
        .macOS(.v13)
    ],
    products: [
        .executable(name: "moqserver", targets: ["Run"]),
        .executable(name: "moq-mcp", targets: ["MoqMCPRun"]),
        .executable(name: "moq-format", targets: ["MoqFormatServiceRun"]),
        .executable(name: "moq-author", targets: ["MoqAuthorRun"]),
    ],
    dependencies: [
        .package(url: "https://github.com/vapor/vapor.git", from: "4.122.0"),
        .package(url: "https://github.com/jpsim/Yams.git", from: "6.2.2"),
        .package(url: "https://github.com/apple/swift-argument-parser.git", from: "1.8.2"),
        .package(url: "https://github.com/apple/swift-log.git", from: "1.15.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.101.3"),
        .package(url: "https://github.com/apple/swift-crypto.git", from: "4.0.0"),
        .package(url: "https://github.com/mattpolzin/OpenAPIKit.git", from: "6.0.0"),
        .package(url: "https://github.com/modelcontextprotocol/swift-sdk.git", from: "0.12.1"),
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
                .product(name: "Crypto", package: "swift-crypto"),
            ],
            path: "Sources/MoqFormat"
        ),

        // MARK: - MoqImport
        // OpenAPI/HAR spec parsing and conversion into MoqProject bundles.
        .target(
            name: "MoqImport",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .product(name: "OpenAPIKit", package: "OpenAPIKit"),
                .product(name: "OpenAPIKit30", package: "OpenAPIKit"),
                .product(name: "OpenAPIKitCompat", package: "OpenAPIKit"),
                .product(name: "Yams", package: "Yams"),
            ],
            path: "Sources/MoqImport"
        ),

        // MARK: - MoqService
        // Transport-neutral .moqproj authoring surface: session model, project mutation, and
        // validation (including a stateless whole-project overload). MoqMCP and moq-format both
        // wrap this rather than each re-implementing it — they differ only in how a call arrives
        // (MCP tool call vs. JSON-RPC over stdio) and how a result is framed.
        .target(
            name: "MoqService",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqImport"),
            ],
            path: "Sources/MoqService"
        ),

        // MARK: - MoqMCP
        // MCP server: tool/resource adapter over MoqService for agent-driven .moqproj authoring.
        // Reusable library — MoqMCPRun below wraps it as the standalone `moq-mcp` executable.
        .target(
            name: "MoqMCP",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqImport"),
                .target(name: "MoqService"),
                .product(name: "MCP", package: "swift-sdk"),
            ],
            path: "Sources/MoqMCP",
            resources: [
                .copy("Resources/schema.json")
            ]
        ),

        // MARK: - MoqMCPRun
        // Thin @main entry point producing the standalone moq-mcp executable (mirrors MoqCLI/Run).
        .executableTarget(
            name: "MoqMCPRun",
            dependencies: [
                .target(name: "MoqMCP")
            ],
            path: "Sources/MoqMCPRun"
        ),

        // MARK: - MoqFormatServiceRun
        // Thin @main entry point producing the standalone moq-format executable: MoqService over
        // JSON-RPC 2.0, Content-Length-framed on stdio (LSP-style framing). Depends only on
        // MoqCore + MoqFormat + MoqImport — deliberately not Vapor, and OpenAPIKit only because
        // MoqImport needs it, so the binary this becomes stays small and fast to start.
        .executableTarget(
            name: "MoqFormatServiceRun",
            dependencies: [
                .target(name: "MoqService")
            ],
            path: "Sources/MoqFormatServiceRun"
        ),

        // MARK: - MoqAuthorCLI
        // Scriptable, non-agent authoring surface over MoqService: one-shot subcommands
        // (`moq-author endpoint upsert`, ...) that open a project, apply one mutation, save, and
        // exit — no long-lived session across invocations, so a shell script or CI step composes
        // several calls the same way it would any other CLI tool. Structured inputs (an endpoint,
        // a variant, an import request) are the same Codable payloads moq-mcp/moq-format decode,
        // passed as a JSON file or stdin — this is deliberately not a from-scratch flag surface
        // for every nested field. Kept out of MoqCLI/Run (the `moqserver` binary) on purpose: that
        // binary's dependency graph stays free of MoqImport/OpenAPIKit, per the note on MoqCLI
        // below and in server/AGENTS.md.
        .target(
            name: "MoqAuthorCLI",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqService"),
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ],
            path: "Sources/MoqAuthorCLI"
        ),

        // MARK: - MoqAuthorRun
        // Thin @main entry point producing the standalone moq-author executable (mirrors
        // MoqCLI/Run and MoqMCP/MoqMCPRun).
        .executableTarget(
            name: "MoqAuthorRun",
            dependencies: [
                .target(name: "MoqAuthorCLI")
            ],
            path: "Sources/MoqAuthorRun"
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
            name: "MoqImportTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqImport"),
            ],
            path: "Tests/MoqImportTests"
        ),
        .testTarget(
            name: "MoqServiceTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqImport"),
                .target(name: "MoqService"),
            ],
            path: "Tests/MoqServiceTests"
        ),
        .testTarget(
            name: "MoqMCPTests",
            dependencies: [
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
                .target(name: "MoqImport"),
                .target(name: "MoqMCP"),
            ],
            path: "Tests/MoqMCPTests"
        ),
        .testTarget(
            name: "MoqCLITests",
            dependencies: [
                .target(name: "MoqCLI"),
                .target(name: "MoqCore"),
            ],
            path: "Tests/MoqCLITests"
        ),
        .testTarget(
            name: "MoqAuthorCLITests",
            dependencies: [
                .target(name: "MoqAuthorCLI"),
                .target(name: "MoqCore"),
                .target(name: "MoqFormat"),
            ],
            path: "Tests/MoqAuthorCLITests"
        ),
    ]
)
