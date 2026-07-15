# Release Checklist

Releases use bare SemVer tags such as `1.2.3` and `1.2.3-rc.1`. Do not prefix tags with `v`. Per-release notes belong in GitHub Releases; do not add a changelog file.

## Before Tagging

- [ ] Confirm the intended version and whether it is stable or an explicitly marked prerelease.
- [ ] Run `make test`, `make smoke`, `make e2e`, `make studio-test`, and `make studio-lint`.
- [ ] Run `make release`, `make docker-build`, and the applicable Studio packaging task.
- [ ] Validate `format/examples/sample-app.moqproj` and `samples/server/showcase.moqproj` with the server.
- [ ] Confirm the canonical format test loads the example through Studio: `cd studio && ./gradlew :studio-project-format:jvmTest --tests "com.moqserver.studio.projectformat.CanonicalFormatCompatibilityTest"`.
- [ ] Build the mobile samples or confirm the `Mobile Samples` workflow is green.
- [ ] Review dependency, CodeQL, and container scan results.
- [ ] Draft GitHub Release notes including compatibility or migration notes.

## Signing Preflight

- [ ] For a stable release, confirm every macOS certificate/notarization and Linux GPG secret in [`RELEASE_SIGNING.md`](RELEASE_SIGNING.md) is configured. Missing credentials intentionally fail the workflow before artifacts are built.
- [ ] Confirm the published Linux GPG public key is current.
- [ ] Use a prerelease tag if unsigned artifacts are intentionally required. Windows MSI artifacts are currently available only for prereleases.

## Tag And Publish

```bash
git tag 1.2.3
git push origin 1.2.3
```

- [ ] Confirm the release workflow passes verification before artifact jobs start.
- [ ] Confirm server archives contain an executable named `moqserver` and pass the packaged-binary validation smoke test.
- [ ] Confirm checksums, signatures where required, SBOM, and GitHub attestations exist.
- [ ] Confirm the GHCR image tag exists and the high/critical vulnerability scan passed.
- [ ] Confirm the GitHub Release is published only after all required jobs succeed.
- [ ] Install each artifact on a supported host and run a basic sample request.
- [ ] Verify `gh attestation verify <artifact> --repo ArjangConsulting/moqserver` for at least one artifact.

## Rollback

Do not reuse or move a published tag. If publication is incorrect, mark the release unavailable, document the reason, fix forward, and publish a new SemVer version.
