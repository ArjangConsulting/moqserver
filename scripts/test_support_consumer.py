#!/usr/bin/env python3
"""Verify a fresh downstream Swift package can consume the public test-support product.

The consumer depends on the repository root, the same manifest an app reaches by repository URL,
and names the package by its identity, `moqserver`, as a URL dependency would.
"""
import json
from pathlib import Path
import subprocess
import tempfile

package = Path(__file__).resolve().parents[1]
assert package.name == "moqserver", f"checkout directory must be named moqserver, got {package.name}"
with tempfile.TemporaryDirectory(prefix="moq-consumer-") as directory:
    root = Path(directory)
    (root / "Package.swift").write_text('''// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "Consumer", platforms: [.macOS(.v10_15)], dependencies: [
    .package(path: %s)
], targets: [.testTarget(name: "ConsumerTests", dependencies: [
    .product(name: "MoqTestSupport", package: "moqserver")
])])
''' % json.dumps(str(package)))
    tests = root / "Tests/ConsumerTests"
    tests.mkdir(parents=True)
    (tests / "ConsumerTests.swift").write_text('''import Foundation
import XCTest
import MoqTestSupport
final class ConsumerTests: XCTestCase {
    func testPublicClient() {
        let client = MoqClient(baseURL: URL(string: "http://localhost:8080")!)
        XCTAssertNil(client.sessionID)
    }
}
''')
    subprocess.run(["swift", "test", "--package-path", directory, "--disable-sandbox"], check=True)
