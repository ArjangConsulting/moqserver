# Authoring with moq-author

`moq-author` is the primary way to create, inspect, edit and validate a `.moqproj` bundle, from a
script, a CI step or an AI agent. Every command is one atomic operation with JSON on stdout.

Download the platform's `moq-author` archive from the release assets, or build locally:

```sh
cd server
swift build --product moq-author
.build/debug/moq-author project create --path /tmp/api.moqproj --name "Example API"
printf '%s' '{"id":"get-users","method":"GET","path":"/users"}' |
  .build/debug/moq-author endpoint upsert --project /tmp/api.moqproj --json -
printf '%s' '{"endpoint_id":"get-users","name":"success","status":200,"default":true,"body":[]}' |
  .build/debug/moq-author variant upsert --project /tmp/api.moqproj --json -
.build/debug/moq-author project validate --project /tmp/api.moqproj --strict
```

## Reading a bundle

```sh
moq-author project describe --project ./api.moqproj
moq-author endpoint list --project ./api.moqproj --method GET --path /users --tag users
moq-author endpoint get --project ./api.moqproj --id get-users
moq-author endpoint suggest-id --method GET --path '/users/{id}'
moq-author schema
moq-author rules
```

None of these saves. `schema` prints the JSON Schema for every document and `rules` prints the
constraints the schema cannot express; both come from the running binary, so they match what it
enforces. `project validate` prints `{"error_count","warning_count","diagnostics"}` and exits 2
when there are errors; `--strict` also exits 2 on warnings such as `W_NO_DEFAULT_VARIANT`. A bundle
that cannot be opened at all is an operational error and exits 1.

A command that saves refuses with `E_PROJECT_IS_WORKING_DIRECTORY` when the working directory is
the bundle or anything under it: a save replaces the bundle directory, which would leave the
calling shell in a directory that no longer exists. Run from outside and pass the path. Read
commands are allowed from anywhere.

Opening a bundle creates `.<bundle>.lock` beside it (for `api.moqproj`, `.api.moqproj.lock`), an
advisory lock that stops two writers (moq-author, Studio, moq-format) from saving over each other.
The file is deliberately left in place, since deleting it could let two writers lock different
files. It holds nothing; keep it out of version control:

```gitignore
.*.moqproj.lock
```

## Add-ons

```sh
moq-author addon list --project ./api.moqproj
printf '%s' '{"verify_signature": false, "trace_claims": ["sub"]}' |
  moq-author addon enable --project ./api.moqproj --id jwt-claims --config -
moq-author addon disable --project ./api.moqproj --id jwt-claims
```

Each prints `{"enabled": {id: config}, "available": [ids]}`. `enable` writes the add-on under
`addons:` in `project.yml`, replacing any config it already had, and omitting `--config` means `{}`.
It checks the id and config before saving and refuses with the validator's code:
`E_UNKNOWN_ADDON` for an id this build doesn't have, or `E_INVALID_ADDON_CONFIG` naming the key, for
example `verify_signature: Required, and must be false`. `disable` does nothing for an add-on that
isn't enabled, and refuses with `E_ADDON_IN_USE` while a variant's `request_match.addons` still
names it. [ADDONS.md](ADDONS.md) lists each add-on's keys.

## Output and errors

Every successful operation emits one JSON document on stdout. Operational errors emit one
`{"code","message"}` JSON document on stderr and exit 1; invalid command arguments use
`E_INVALID_ARGUMENTS` and exit 64. `--help` is human-readable and exits 0. Routine library logging
is disabled in this executable so scripts can parse the streams directly.

Each command opens, mutates, and saves independently. Concurrent commands may return
`E_PROJECT_BUSY` or `E_PROJECT_CHANGED`; rerun the operation against the latest state after
reviewing the conflict. A sequence of commands is not a multi-command transaction.

Variant upsert returns `outcome: created` or `outcome: replaced` and `previous_name` when replaced.
Names match case-insensitively: do not remove an older casing after replacement.

```sh
moq-author import har --project ./api.moqproj --har-path capture.har
moq-author import openapi --project ./api.moqproj --source specification.yaml
```

HAR import redacts credentials by the same [credential names](RUNTIME_WORKFLOWS.md#credential-names)
as request-history capture, including the target bundle's `redact:` section and declared auth
headers. Because a capture is committed to the bundle, it also redacts `X-Forwarded-For` and
`WWW-Authenticate` headers and the parameters, form fields and JSON keys `code`, `state`, `auth`,
`key`, `sig`, `signature`, `client_id`, `code_challenge`, `challenge`, `email`,
`authenticity_token` and `SAMLRequest`; a redacted query parameter imports as a presence-only rule.
JWT signatures are stripped from every other value. Add `--redact-header <name>` or
`--redact-query-parameter <name>` (both repeatable) for names your API uses. `import.parseHar` over
`moq-format` takes `redactHeaders`, `redactQueryParameters`, `exceptHeaders` and
`exceptQueryParameters`, since it opens no bundle. Search a capture for tokens before importing: a
custom credential header nobody listed is kept as captured unless its value is a JWT.

`--infer-header-rules` (`inferHeaderRules` over `moq-format`) adds presence-only `request_rules`
headers to each imported endpoint: credential headers (by the same rules) and custom `X-` headers
that every successful captured call to that endpoint sent. If no call succeeded, every call counts.
Only names are written, never values, so an imported endpoint then rejects a request that drops,
say, `Authorization`, and the test sees a `request_rule` rejection naming it. Transport, caching and
browser headers (`Accept`, `User-Agent`, `Sec-*`, `Cookie`, ...) are never inferred.

OpenAPI import maps an operation's security (or the document's) as follows. All schemes listed
in one requirement object are required together. The first Authorization scheme (http bearer or
basic, oauth2, openIdConnect), or else the first header API key, becomes the endpoint's `auth`.
Every other scheme becomes a required `request_rules` entry: a header API key a header rule, a
cookie API key (`in: cookie`) a cookie rule, and a query API key a query rule. Schemes are taken in
a fixed order, so the same spec always imports the same way. When the spec lists alternative
requirement objects, only the first is imported. The import result's `warnings` report this, along
with schemes it can't express (mutualTLS, other http schemes, a second Authorization scheme).

OpenAPI variants are named after their status so tests can select them by a short, stable name:
the reason phrase in camelCase (`ok`, `created`, `noContent`, `unauthorized`, `notFound`,
`unprocessableContent`), the class for a range (`clientError` for `4XX`), or `status<code>` for a
code with no standard phrase. The response's `description` becomes the variant's `description`. A
second content type adds its suffix (`ok_xml`); a media type with several named `examples` gets one
variant per example (`ok_empty`, `ok_fullPage`). Re-importing never renames existing variants.

OpenAPI query parameters become `request_rules.query_params` from their schema's value
constraints: `enum` an anchored `matches_regex` of the listed values (`^(?:open|done)$`), `pattern` a
`matches_regex` as written, and `minimum`/`maximum` (or their exclusive forms) `gte`/`gt`/`lte`/`lt`.
A parameter with two constraints gets two rules. Each rule is `required: true` for a required
parameter and `required: false` for an optional one, so an app that omits an optional parameter
still passes, while one that sends an illegal value gets a `query_param_value_mismatch` rejection.
A required parameter with no constraint gets a presence rule. An `example` or `default` adds no
rule: it documents one legal value, not the only one. With `update_details`, re-importing replaces
query rules that differ from the spec's. Cookie parameters import as presence rules.

Declared response `headers` that have an example (`example`, `examples`, or a schema example) are
copied into the variant. A header with no example is reported in `warnings`, not given a made-up
value.

Imports preserve existing endpoint details and bodies unless options explicitly enable changes:

```json
{"update_details": true, "replace_existing_bodies": false, "accept_paths": ["/users"]}
```

Pass that file with `--options options.json`. This preservation default applies to `moq-author`;
other service adapters may explicitly choose different import policies. URL import requires
`MOQ_AUTHOR_ALLOW_NETWORK=1`; local file import does not. Use `--auth-json` for URL credentials
rather than putting them in a URL or command-line token argument.

Validate before serving: authoring permits incomplete projects while a script is assembling them.
See [format documentation](FORMAT_IMPLEMENTATION.md) for document fields and
[error catalog](ERROR_CATALOG.md) for persistence failures.
