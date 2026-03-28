# Release Checklist

## Pre-Release

- [ ] All tests pass: `make test`
- [ ] Integration and smoke tests pass: `make e2e`
- [ ] Build succeeds in release mode: `make release`
- [ ] Docker image builds: `make docker-build`
- [ ] Run smoke test against Docker image manually
- [ ] Review spec validation warnings for bundled examples
- [ ] Verify companion starts and `/health` responds

## Version Bump

- [ ] Update version in relevant files (if applicable)
- [ ] Update CHANGELOG with release notes
- [ ] Commit version bump

## Validation

- [ ] `swift run moqserver serve --spec ./openapi.yaml --port 8080` starts without errors
- [ ] `swift run moqserver companion --port 8081` starts and lists providers
- [ ] `swift run moqserver init --spec ./openapi.yaml --output ./mocks` scaffolds files
- [ ] `swift run moqserver validate-spec --spec ./openapi.yaml` reports no errors
- [ ] Admin API (`GET /_admin/endpoints`) returns registered endpoints
- [ ] Variant selection via `X-Mock-Variant` header works
- [ ] Auth enforcement works (bearer, basic, API key)
- [ ] Content negotiation via `Accept` header works

## Studio Packaging

- [ ] Studio builds: `make studio-build`
- [ ] Studio runs: `make studio-run`
- [ ] Package macOS DMG: `make studio-dmg`
- [ ] Package Linux deb: `make studio-deb` (on Linux)
- [ ] Verify .app opens and can load a `.moqproj` project
- [ ] (Optional) Code sign: set `MOQSERVER_STUDIO_SIGNING_IDENTITY` env var
- [ ] (Optional) Notarize: set `MOQSERVER_STUDIO_APPLE_ID`, `MOQSERVER_STUDIO_NOTARIZATION_PASSWORD`, `MOQSERVER_STUDIO_TEAM_ID`

## Release

- [ ] Create git tag: `git tag v<version>`
- [ ] Push tag: `git push origin v<version>`
- [ ] Build release binary: `make release`
- [ ] Verify release binary runs correctly
- [ ] Build Studio packages for target platforms
- [ ] Attach Studio DMG/deb to GitHub release
