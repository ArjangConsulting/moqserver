---
name: moqserver-authoring
description: Author and edit .moqproj mock bundles with the moq-mcp tools — project/endpoint/variant lifecycle, HAR/OpenAPI import, and the traps that have caused real data loss.
---

# Authoring a `.moqproj` bundle

Use this when creating or editing a moqserver mock bundle through the `moq-mcp` tools
(`moq_create_project`, `moq_open_project`, `moq_upsert_endpoint`, `moq_upsert_variant`, ...).

## The bundle shape

A `.moqproj` is a directory: `project.yml` (manifest + `global_rules` + auth/network defaults),
one `endpoints/<id>.yml` per endpoint, and `fixtures/` holding externalized response bodies
(`body_file`). You almost never write these files directly — the MCP tools own them.

## The normal tool sequence

1. `moq_create_project` (new bundle) or `moq_open_project` (existing one).
2. `moq_upsert_endpoint` per route — creates or replaces one endpoint's metadata (method, path,
   auth, request_rules). Reserved paths (`/health`, `/_admin*`, `/_auth*`) are rejected.
3. `moq_upsert_variant` per response shape on that endpoint — inline `body`, never `body_file`;
   the store externalizes it to a fixture at save time.
4. `moq_validate_project` — check **both** `errorCount` and `warningCount`. Errors block save;
   warnings (e.g. no default variant) don't, but they're telling you something real.
5. `moq_save_project`.

Read `moq://schema/moqproj.json` and `moq://docs/authoring-rules` when unsure of a field's shape
or a validation rule's exact wording — they're generated from the real schema/validator, not
hand-copied, so they can't drift from what actually gets enforced.

## Traps — each of these has caused real data loss or real confusion

- **Filter the capture before importing.** `moq_import_har` imports every request in the file.
  A raw device/browser capture is full of analytics pings, ad requests, and traffic to domains
  you don't own. Trim the HAR (or use the importer's selection/filter input) to the endpoints you
  actually want mocked *before* importing — cleaning up a bundle after the fact is much more work
  than not importing the noise in the first place.

- **Never hand-edit a bundle's YAML while an MCP session holds it open.** Every mutation tool
  (`moq_upsert_endpoint`, `moq_remove_endpoint`, `moq_upsert_variant`, `moq_remove_variant`) fails
  fast with `E_PROJECT_CHANGED` if the bundle changed on disk since it was opened — that's
  intentional, not a bug to work around. If you need to hand-edit, close the session's hold on the
  file first (or reopen with `moq_open_project` afterward) rather than fighting the error.

- **Variant names are case-insensitive.** `Success` and `success` are the *same* variant.
  `moq_upsert_variant` with a name that matches an existing one in any casing **replaces it in
  place** — the response text says `Replaced variant ...`, not `Created`. Read that response
  before issuing a follow-up `moq_remove_variant`; removing "the old spelling" after a case-only
  upsert deletes the variant you just wrote, not a leftover duplicate.

- **Generated variant names are lowercase by convention** (`success`, `error` from HAR/OpenAPI
  import). Keep hand-authored variant names lowercase too, so a later import into the same bundle
  can't silently collide with — or shadow — one you wrote by hand.

- **A missing default variant is a warning, not an error.** `moq_validate_project` passes clean on
  an endpoint that has variants but none marked `default: true` — selection then falls back to
  declaration order, so a variant declared first (often an error response, if that's how the
  import happened to order things) silently wins under normal content negotiation. Treat
  `W_NO_DEFAULT_VARIANT` as something to fix, not ignore.

## See also

- `moqserver-request-validation` — `request_rules`/`global_rules`, and the bundle-wide-auth trap.
- `moqserver-scenario-design` — choosing `call_count` vs runtime variant selection.
- `moqserver-serving-for-tests` — running the bundle you just authored.
