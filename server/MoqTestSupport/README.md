# MoqTestSupport

`MoqControl` drives a running `moqserver` instance from an iOS/macOS app's own UI or integration
tests — select a variant, reset one, or reset a call counter — via moqserver's admin API
(`docs/ADMIN_API.md`). It exists because the app under test sends its own headers, so
`X-Mock-Variant` can't select a scenario from the test side; only the admin API can reach in from
outside.

Originally written inline in a consuming app (Novalingo's `NovalingoUITestSupport/MoqControl.swift`)
and lifted here so every app pointing at moqserver gets it rather than re-implementing the same
HTTP calls. See `server/skills/moqserver-scenario-design` for when to reach for this versus a
bundle's own `call_count`.

## Adding it to an app

Add this directory as a local Swift Package dependency (Xcode: File > Add Package Dependencies >
Add Local...) to your test target, or reference it by path in your own `Package.swift`:

```swift
.package(path: "../moqserver/server/MoqTestSupport")
```

then depend on the `MoqTestSupport` product from your UI test target.

## Usage

```swift
import MoqTestSupport
import XCTest

final class VideoDetailsUITests: XCTestCase {
    override func tearDown() {
        MoqControl.resetAll(for: "GET", path: "/v1/videos/1440/")
        super.tearDown()
    }

    func testRetryAfterServerError() throws {
        try MoqControl.selectVariant("serverError", for: "GET", path: "/v1/videos/1440/")
        // …launch the app, navigate to the screen, assert the error state…

        try MoqControl.resetVariant(for: "GET", path: "/v1/videos/1440/")
        // …tap Retry, assert the content loaded…
    }
}
```

`MoqControl.baseURL` defaults to `http://127.0.0.1:8080`, matching a locally launched
`moqserver serve` on its default port — set it in test-plan setup if your suite uses a different
port. `MoqControl.adminAuth` attaches admin credentials (`.bearer` / `.apiKey`) if the target
server's config requires them; leave it `nil` for the common unauthenticated-loopback setup.
`MoqControl.waitUntilReady(timeout:)` polls until the server responds, for a test plan that
launches `moqserver` itself rather than assuming it's already up.

**Reset what you set.** Overrides live in the running server process, not in the bundle — a test
that sets one and doesn't clear it leaks the override into whatever test runs next. Prefer
`resetAll(for:path:)` in `tearDown`.
