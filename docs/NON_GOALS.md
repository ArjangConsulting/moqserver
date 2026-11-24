# Non-goals

These are things moqserver deliberately does not do. Each entry gives the reason and what to do
instead. Check this list before asking for one of them; a proposal to change an entry should explain
why the alternative given here does not work.

## Recording or proxying live traffic

moqserver has no record mode, pass-through mode or proxy to a real backend, and will not add one.

- **Why:** a mock server that forwards traffic has to hold real credentials and reach real services.
  That breaks the guarantees moqserver is built on: it is offline and deterministic, and it never
  stores a raw credential or cookie value.
- **Instead:** capture traffic with a tool built for it, such as a debugging proxy, your platform's
  network recorder or browser developer tools. Export it as HAR and import it:
  `moq-author import har --project ./api.moqproj --har-path capture.har`
  ([AUTHORING_CLI.md](AUTHORING_CLI.md)). The import redacts credential headers, and
  `--infer-header-rules` turns the headers every call sent into required-header rules.

## Deciding which test a request belongs to

moqserver attributes a request to a session only by its `X-Mock-Session` header. It does not try to
tell one test's leftover traffic from another's. For example, it will not ignore requests from an app
whose session was already released.

- **Why:** a request that arrives with a stale or missing session ID is a real bug in the app or the
  test harness. Filtering it out on the server would hide exactly what a session check is meant to
  catch.
- **Instead:** keep the app's lifetime inside its session. Stop the app before releasing the session
  in teardown, or launch the app once per test. Have the app read its session ID each time it sends a
  request rather than caching it at startup.
  See [Parallel tests](RUNTIME_WORKFLOWS.md#parallel-tests).

## Prescribing how an app holds its session ID

moqserver defines the `X-Mock-Session` header and the session admin routes, nothing more. How an app
receives the session ID, stores it and names it internally (launch argument, environment, build
configuration or anything else) is the app's choice. moqserver's docs and clients do not prescribe it.
An app whose APIs need no per-test state can send no session header and use global state.

## Other non-goals

- **Real authentication.** Auth rules and the `oauth-mock` add-on simulate what an app expects to
  see. Session IDs select test state; they are not a security boundary.
- **OpenAPI compliance validation.** Request rules check what a bundle declares, not a whole
  specification.
- **Performance or load testing.** The server is built for functional tests and local development.
