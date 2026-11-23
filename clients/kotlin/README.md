# moq-test-support (Kotlin/JVM)

Drives a running `moqserver` from JVM or Android instrumentation tests. It's the Kotlin counterpart
of the Swift [`MoqTestSupport`](../../server/MoqTestSupport) package, with the same operations:
sessions, variant selection, scenarios, and request history.

## Adding it

Distributed through [JitPack](https://jitpack.io), pinned by commit SHA. JitPack names the artifact
after the repository, so the coordinates are `com.github.ArjangConsulting:moqserver:<sha>`. Use the
short 10-character SHA, e.g. `05ec58b4d3`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    androidTestImplementation("com.github.ArjangConsulting:moqserver:<commit-sha>")
}
```

It depends only on `kotlinx-serialization-json` and targets Java 17 bytecode. HTTP goes through
`java.net.HttpURLConnection`, which is available on Android.

## Usage

Calls on `MoqClient` block the calling thread, so never make them from Android's main thread
(instrumentation tests already run on a background thread); use `client.suspending` from coroutines.

```kotlin
// From an Android emulator the host's loopback is 10.0.2.2.
val admin = MoqClient("http://10.0.2.2:8080", auth = MoqClient.Auth.Bearer("admin-token"))
check(admin.waitUntilReady())

val session = admin.createSession()
// Configure the app to send `X-Mock-Session: ${session.sessionId}` on its mock requests.
session.activateScenario("checkout-fails")          // declared under scenarios: in project.yml
session.selectVariant("error", "GET", "/users/{id}")

// ... drive the app ...

session.assertNoUnmatchedRequests()                  // fails on any path the bundle doesn't mock
val sent = session.requests().first { it.path == "/ai" }.requestBody?.jsonObject()  // needs --capture-request-bodies
session.closeSession()
```

### Asserting what the app sent

The server evaluates these checks (`POST /_admin/requests/query`), so Kotlin, Swift and scripts
agree on what they mean. A failed check throws `MoqRequestAssertionException` (an
`AssertionError`), whose message lists the near misses or the offending requests. Header
expectations need `serve --capture-request-headers`; claims come from the `jwt-claims` add-on.

```kotlin
// GET /v1/me was called twice with this user's Bearer token, the retry with a newer token.
val calls = session.assertRequest(
    "GET /v1/me", times = 2,
    headers = listOf(MoqHeaderExpectation.Scheme("Authorization", "Bearer")),
    claims = mapOf("sub" to uid),
)
assertTrue(calls[0].claims.getValue("iat").toLong() < calls[1].claims.getValue("iat").toLong())

// No Authorization header on the content call.
session.assertRequest("GET /content/{id}", headers = listOf(MoqHeaderExpectation.Absent("Authorization")))

// Nothing after sign-out carried a token.
val signedOut = session.mark()
session.assertRequest(
    "GET /v1/me", times = 0, headers = listOf(MoqHeaderExpectation.Present("Authorization")), since = signedOut,
)

// After the test: no request was rejected, and none from this test's user skipped the session.
session.assertNoRejectedRequests()
session.assertAllRequestsCarrySession(claims = mapOf("sub" to uid))
```

`times = null` means at least once. `timeoutMillis` re-checks until the app has made the call.
Without `--require-session`, the session check covers global requests recorded after the session
was created, so app start-up traffic before `createSession()` doesn't count. Calls that carry another,
already released session's id (a finished run's app still running in the background) don't count
either, unless you pass `includeOtherSessions = true`.

To test a retry, fail only the next call in this session:

```kotlin
session.selectVariant("unauthorized", "GET", "/v1/me", times = 1)
```

Inspect and drive server state without hand-written HTTP:

```kotlin
val endpoints = admin.endpoints()                    // keys, variants, active overrides, call counts
val calls = admin.callCount("POST", "/checkout")
admin.defineScenario("checkout-down", mapOf("POST /checkout" to "error"))
admin.selectVariant("error", "POST", "/graphql", operation = "GetUser")
admin.reload()                                       // re-read the bundle from disk
```

Coroutine tests use `client.suspending`, which offers every call as a `suspend` function run on
`Dispatchers.IO` (this adds `kotlinx-coroutines-core` to the dependency):

```kotlin
runTest {
    val session = admin.suspending.createSession()
    session.suspending.activateScenario("checkout-fails")
    assertEquals(0, session.suspending.callCount("POST", "/checkout"))
}
```

Failures throw `MoqControlException` (`TimedOut`, `Transport`, or `Rejected` with the status and
body). `assertNoUnmatchedRequests()` throws `MoqUnmatchedRequestsException`, an `AssertionError`,
so it reads as a test failure.

## Developing

```bash
cd clients/kotlin
./gradlew test
```
