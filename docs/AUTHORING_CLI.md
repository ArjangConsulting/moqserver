# Scripted authoring with moq-author

Download the platform's `moq-author` archive from the release assets, or build locally:

```sh
cd server
swift build --product moq-author
.build/debug/moq-author project create --path /tmp/api.moqproj --name "Example API"
printf '%s' '{"id":"get-users","method":"GET","path":"/users"}' |
  .build/debug/moq-author endpoint upsert --project /tmp/api.moqproj --json -
printf '%s' '{"endpoint_id":"get-users","name":"success","status":200,"default":true,"body":[]}' |
  .build/debug/moq-author variant upsert --project /tmp/api.moqproj --json -
.build/debug/moqserver validate --project /tmp/api.moqproj
```

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
