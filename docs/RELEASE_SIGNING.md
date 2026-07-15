# Release Signing

Stable releases fail during preflight unless all required macOS signing/notarization and Linux signing credentials are available. Explicit SemVer prereleases, for example `1.2.3-rc.1`, may publish unsigned packages. Every artifact also receives a portable OpenSSL SHA-256 checksum and GitHub build-provenance attestation.

| Platform | Stable release policy | Required secrets |
|----------|-----------------------|------------------|
| macOS | Developer ID signed, notarized, and stapled | All `APPLE_*` secrets below |
| Linux | GPG detached signature | `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` |
| Windows | No stable MSI until Authenticode is configured | Prerelease MSI is unsigned |

## macOS

Create a Developer ID Application certificate for distribution outside the App Store, export it as a password-protected PKCS#12 file, and create an Apple ID app-specific password for `notarytool`.

| Secret | Value |
|--------|-------|
| `APPLE_CERTIFICATE_P12_BASE64` | Base64-encoded `.p12` certificate |
| `APPLE_CERTIFICATE_PASSWORD` | `.p12` export password |
| `APPLE_SIGNING_IDENTITY` | `Developer ID Application: Name (TEAMID)` |
| `APPLE_ID` | Notarization Apple ID |
| `APPLE_NOTARIZATION_PASSWORD` | App-specific password |
| `APPLE_TEAM_ID` | Apple Developer Team ID |

Encode the certificate on macOS with `base64 -i DeveloperID.p12 | pbcopy`. The workflow imports it into a temporary keychain and runs `:composeApp:notarizeDmg` for stable tags.

## Linux

Generate a dedicated release key and keep only its public key in public distribution channels:

```bash
gpg --quick-generate-key "moqserver releases" rsa4096 sign 2y
gpg --armor --export-secret-keys <KEY_ID> > moqserver-release-private.asc
gpg --armor --export <KEY_ID> > moqserver-release-public.asc
```

Store the private key content in `GPG_PRIVATE_KEY` and its passphrase in `GPG_PASSPHRASE`. Users verify a release with:

```bash
gpg --verify moqserver-studio_*.deb.asc moqserver-studio_*.deb
openssl dgst -sha256 -r moqserver-studio_*.deb
```

Compare the OpenSSL digest to the downloaded `.sha256` file. The checksum files use the widely supported `<digest> *<filename>` format and contain no runner-specific paths.

## Provenance

GitHub generates Sigstore-backed attestations for server archives, Studio packages, and the GHCR image. After downloading an artifact:

```bash
gh attestation verify <artifact> --repo ArjangConsulting/moqserver
```

Checksums detect corruption; GPG or platform signing establishes publisher identity; attestations tie an artifact to the GitHub Actions build.

## Windows

The release workflow intentionally omits MSI files from stable releases until Authenticode signing is configured through a hardware- or cloud-backed key provider. Azure Trusted Signing or `jsign` with a supported KMS are suitable future integrations. Do not weaken stable preflight to publish an unsigned MSI.
