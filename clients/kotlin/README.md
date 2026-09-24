# moq-test-support (Kotlin/JVM)

Drives a running `moqserver` from JVM or Android instrumentation tests. It's the Kotlin counterpart
of the Swift [`MoqTestSupport`](../../server/MoqTestSupport) package, with the same operations:
sessions, variant selection, scenarios, and request history.

## Adding it

Distributed through [JitPack](https://jitpack.io), pinned by commit SHA:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    androidTestImplementation("com.github.ArjangConsulting.moqserver:moq-test-support:<commit-sha>")
}
```

It depends only on `kotlinx-serialization-json` and targets Java 17 bytecode. HTTP goes through
`java.net.HttpURLConnection`, which is available on Android.

## Usage

Calls block the calling thread, so never make them from Android's main thread. Instrumentation
tests already run on a background thread.

```kotlin
// From an Android emulator the host's loopback is 10.0.2.2.
val admin = MoqClient("http://10.0.2.2:8080", auth = MoqClient.Auth.Bearer("admin-token"))
check(admin.waitUntilReady())

val session = admin.createSession()
// Configure the app to send `X-Mock-Session: ${session.sessionId}` on its mock requests.
session.activateScenario("out-of-credits")          // declared under scenarios: in project.yml
session.selectVariant("error", "GET", "/users/{id}")

// ... drive the app ...

session.assertNoUnmatchedRequests()                  // fails on any path the bundle doesn't mock
val sent = session.requests().first { it.path == "/ai" }.requestBody?.jsonObject()  // needs --capture-request-bodies
session.closeSession()
```

Failures throw `MoqControlException` (`TimedOut`, `Transport`, or `Rejected` with the status and
body). `assertNoUnmatchedRequests()` throws `MoqUnmatchedRequestsException`, an `AssertionError`,
so it reads as a test failure.

## Developing

```bash
cd clients/kotlin
./gradlew test
```
