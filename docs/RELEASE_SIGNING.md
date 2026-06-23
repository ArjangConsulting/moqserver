# Release Signing

How Studio release artifacts are signed across platforms. Signing in the
`Release` workflow (`.github/workflows/release.yml`) is **secret-gated**: if a
platform's secrets are absent, the workflow still builds and uploads **unsigned**
artifacts plus SHA-256 checksums, so a release never blocks on certificates.

| Platform | Mechanism | Status | Required secrets |
|----------|-----------|--------|------------------|
| macOS | Developer ID signing + notarization (notarytool) | Wired | `APPLE_*` (below) |
| Linux | GPG detached signature (`.deb.asc`) + SHA-256 | Wired (key optional) | `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` |
| Windows | Authenticode on the MSI | Unsigned for now | — (see below) |

All artifacts always get a `*.sha256` checksum file regardless of signing.

## macOS

Signing/notarization is configured in `studio/composeApp/build.gradle.kts`
(`compose.desktop.application.nativeDistributions.macOS`) and driven by env vars,
which CI maps from secrets.

**Prerequisites**

1. Apple Developer Program membership ($99/year).
2. A **Developer ID Application** certificate. Create it in the Apple Developer
   portal, install into your login keychain, then export as a `.p12` (with a
   password). This cert is for distribution **outside** the App Store.
3. An **app-specific password** for your Apple ID (appleid.apple.com → Sign-In and
   Security → App-Specific Passwords) for notarytool.
4. Your **Team ID** (Apple Developer → Membership).

**Repository secrets**

| Secret | Value |
|--------|-------|
| `APPLE_CERTIFICATE_P12_BASE64` | `base64 -i DeveloperID.p12` output |
| `APPLE_CERTIFICATE_PASSWORD` | password used when exporting the `.p12` |
| `APPLE_SIGNING_IDENTITY` | e.g. `Developer ID Application: Your Name (TEAMID)` |
| `APPLE_ID` | Apple ID email used for notarization |
| `APPLE_NOTARIZATION_PASSWORD` | the app-specific password |
| `APPLE_TEAM_ID` | your 10-character Team ID |

With all of the above present, CI runs `:composeApp:notarizeDmg` (signs the app,
builds the DMG, submits to notarytool, and staples the ticket). With only the
certificate secrets, it signs but does not notarize. With none, it builds an
unsigned DMG.

To base64-encode the certificate:

```bash
base64 -i DeveloperID.p12 | pbcopy   # macOS, copies to clipboard
```

## Linux

Linux has no OS-level code signing. We publish a **detached GPG signature** and a
SHA-256 checksum next to the `.deb` so users can verify authenticity.

**Generate a signing key (one time)**

```bash
gpg --quick-generate-key "moqserver releases <releases@moqserver.example>" rsa4096 sign 2y
gpg --armor --export-secret-keys <KEY_ID> > moqserver-release-private.asc
gpg --armor --export <KEY_ID> > moqserver-release-public.asc   # publish this
```

**Repository secrets**

| Secret | Value |
|--------|-------|
| `GPG_PRIVATE_KEY` | contents of `moqserver-release-private.asc` |
| `GPG_PASSPHRASE` | passphrase for that key |

Publish the **public** key (`moqserver-release-public.asc`) in the repo or release
notes so users can run:

```bash
gpg --import moqserver-release-public.asc
gpg --verify moqserver-studio_*.deb.asc moqserver-studio_*.deb
sha256sum -c moqserver-studio_*.deb.sha256
```

## Windows (future)

Windows artifacts are currently **unsigned** (the MSI is built and checksummed but
not Authenticode-signed). Since June 2023, OV code-signing keys must live on
hardware or a cloud HSM, so a plain `.pfx` on a runner is not an option.

Recommended path when ready:

- **Azure Trusted Signing** (~$10/month) — sign with
  [`azure/trusted-signing-action`](https://github.com/Azure/trusted-signing-action),
  or
- **jsign** — a pure-Java Authenticode signer that can run on the Linux runner and
  sign the MSI via Azure Key Vault / AWS KMS / GCP KMS, avoiding a Windows signing
  job entirely.

When adopted, add the signing step after `:composeApp:packageMsi` in the
`studio-windows` job and gate it on the cloud-HSM secrets, mirroring the macOS and
Linux pattern.
