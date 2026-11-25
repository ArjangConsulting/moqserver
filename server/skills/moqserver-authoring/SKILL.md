---
name: moqserver-authoring
description: Author and edit .moqproj mock bundles with the moq-author CLI — project/endpoint/variant lifecycle, HAR/OpenAPI import, validation, and the traps that have caused real data loss.
---

# Authoring a `.moqproj` bundle

Use this when creating or editing a moqserver mock bundle. The tool is the `moq-author` CLI: every
command opens the bundle, does one thing, saves, and exits, so there is no session to keep in
sync and nothing to register with your client.

```sh
swift build --package-path <moqserver>/server --product moq-author
<moqserver>/server/.build/debug/moq-author --help
```

Release archives also ship a prebuilt `moq-author`.

## The bundle shape

A `.moqproj` is a directory: `project.yml` (manifest + `global_rules` + auth/network defaults),
one `endpoints/<id>.yml` per endpoint, and `fixtures/` holding externalized response bodies
(`body_file`). Let `moq-author` write these files; hand-edit only what it has no command for.

## Look before you write

```sh
moq-author schema                      # the JSON Schema for every document
moq-author rules                       # rules the schema cannot express
moq-author project describe --project api.moqproj
moq-author endpoint list --project api.moqproj [--method GET] [--path /users] [--tag users]
moq-author endpoint get --project api.moqproj --id get-users
```

`schema` and `rules` are generated from the real schema and validator, so they cannot drift from
what is enforced. Read them when unsure of a field's shape instead of guessing.

## The normal sequence

1. `moq-author project create --path api.moqproj --name "My API"` for a new bundle.
2. `moq-author endpoint upsert --project api.moqproj --json endpoint.json` per route. It creates
   or replaces one endpoint's metadata (method, path, auth, `request_rules`). Reserved paths
   (`/health`, `/_admin*`, `/_auth*`) are rejected. `moq-author endpoint suggest-id --method GET
   --path /users/{id}` prints the id the importers would use.
3. `moq-author variant upsert --project api.moqproj --json variant.json` per response shape. Pass
   an inline `body`, never `body_file`; the store externalizes it to a fixture on save.
4. If variants match on token claims, enable the add-on first:
   `printf '%s' '{"verify_signature": false}' | moq-author addon enable --project api.moqproj --id jwt-claims --config -`.
   `addon list` shows what is enabled and what this build provides; `addon disable` refuses while
   a variant still matches on the add-on. Don't edit `project.yml` by hand for this.
5. `moq-author project validate --project api.moqproj --strict`. It prints every diagnostic as
   JSON and exits 2 on errors; `--strict` also fails on warnings. Run it after every batch of
   edits, since authoring deliberately allows an incomplete bundle while you assemble it.

Structured input is a JSON file, or stdin with `--json -`:

```sh
printf '%s' '{"endpoint_id":"get-users","name":"empty","status":200,"body":[]}' |
  moq-author variant upsert --project api.moqproj --json -
```

Success prints one JSON document on stdout. Failure prints `{"code","message"}` on stderr and
exits non-zero; branch on `code` (`E_...`), not on the message text.

## Importing a capture or a spec

```sh
moq-author import har --project api.moqproj --har-path capture.har --options options.json
moq-author import openapi --project api.moqproj --source openapi.yaml
```

`options.json` is `{"accept_paths": ["/v1/users"], "update_details": false,
"replace_existing_bodies": false}`. Imports keep existing endpoint details and bodies unless you
turn those flags on. Importing a URL needs `MOQ_AUTHOR_ALLOW_NETWORK=1`; a local file never does.

## Traps — each of these has caused real data loss or real confusion

- **Filter the capture before importing.** A raw device or browser HAR is full of analytics
  pings, ad requests, and traffic to domains you don't own. Name the endpoints you want first and
  pass them as `accept_paths`. Cleaning a bundle afterwards is far more work.

- **Check a capture for secrets before it touches the bundle.** Search the HAR for `Bearer `,
  `eyJ`, `token`, `password` and email addresses. Anything not already redacted becomes a
  committed fixture. Header names containing `auth`, `token`, `key`, `secret`, `session` or
  `appcheck` are redacted automatically; pass `--redact-header <name>` for any other custom auth
  header the app sends. Add `--infer-header-rules` to require (by name only) the auth and `X-`
  headers every successful captured call sent, so a call that drops its token is rejected visibly.

- **Variant names are case-insensitive.** `Success` and `success` are the *same* variant.
  Upserting a name that matches an existing one in any casing **replaces it in place**: the
  result says `"outcome": "replaced"` with `previous_name`. Read that before removing "the old
  spelling", or you delete the variant you just wrote.

- **Keep variant names lowercase.** The importers generate `success`, `error`. A hand-authored
  `Success` will collide with a later import into the same bundle.

- **A missing default variant is a warning, not an error.** An endpoint with variants but none
  marked `default: true` falls back to declaration order, so whichever variant was declared first
  (often an error response) silently wins. `project validate --strict` catches it as
  `W_NO_DEFAULT_VARIANT`.

- **Run `moq-author` from outside the bundle directory.** A save replaces the bundle directory
  as a whole, which would leave a shell sitting inside it in a directory that no longer exists,
  so every command that saves refuses with `E_PROJECT_IS_WORKING_DIRECTORY`. Pass the bundle's
  path instead of `cd`-ing into it. Read commands work from anywhere.

- **A save rewrites only the documents that changed.** `project.yml` and untouched endpoint files
  keep their bytes, comments included. The endpoint you edit is regenerated, so comments inside
  that one file are lost; put lasting notes in `description` fields.

- **A sequence of commands is not a transaction.** Each command saves on its own. If two run
  against the same bundle at once, one fails with `E_PROJECT_BUSY` or `E_PROJECT_CHANGED`; rerun
  it against the new state.

## Using an MCP client instead

`moq-mcp` exposes the same operations as MCP tools (`moq_upsert_variant`, ...) for clients with
no shell. It is in maintenance and gets no new capability. It also holds the bundle open in
memory, which adds one trap the CLI does not have: hand-editing the YAML while a session is open
makes every later mutation fail with `E_PROJECT_CHANGED` until you reopen the project. Prefer the
CLI whenever you can run a command.

## See also

- `moqserver-request-validation` — `request_rules`/`global_rules`, and the bundle-wide-auth trap.
- `moqserver-scenario-design` — choosing `call_count` vs runtime variant selection.
- `moqserver-serving-for-tests` — running the bundle you just authored.
- `docs/AUTHORING_CLI.md` — the full command reference.
