# Release Checklist

## Pre-Release

- [ ] All tests pass: `make test`
- [ ] Integration and smoke tests pass: `make e2e`
- [ ] Build succeeds in release mode: `make release`
- [ ] Docker image builds: `make docker-build`
- [ ] Run smoke test against Docker image manually
- [ ] Review project validation warnings for bundled sample projects

## Version Bump

- [ ] Update version in relevant files (if applicable)
- [ ] Update `CHANGELOG.md` with release notes
- [ ] Commit version bump

## Validation

- [ ] `cd server && swift run Run serve --project ../samples/server/showcase.moqproj --config ../samples/server/config.yaml --port 8080` starts without errors
- [ ] `cd server && swift run Run validate --project ../samples/server/showcase.moqproj` reports no errors
- [ ] Admin API (`GET /_admin/endpoints`) returns registered endpoints
- [ ] Variant selection via `X-Mock-Variant` header works
- [ ] Auth enforcement works (bearer, basic, API key)
- [ ] Content negotiation via `Accept` header works

## Studio Packaging

- [ ] Studio builds: `make studio-build`
- [ ] Studio runs: `make studio-run`
- [ ] Verify AI provider settings and Test Connection succeed for configured providers
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
