# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest stable release | Yes |
| Current `main` branch | Yes |
| Prereleases and older releases | Best effort; reproduce on latest stable |

## Reporting a Vulnerability

Please do not open public issues for suspected vulnerabilities.

Report privately with the repository's **Security > Report a vulnerability** form. If private vulnerability reporting is unavailable, contact the repository owner privately; do not disclose the issue publicly.

Helpful details include:

- affected command or app workflow
- input files needed to reproduce the issue
- expected and actual behavior
- whether credentials, imported HAR data, or generated mock output are involved

## Secrets and Test Data

Do not include real API keys, bearer tokens, cookies, HAR captures with live credentials, private OpenAPI specs, or production `.moqproj` bundles in issues or pull requests.

The sample credentials in this repository are mock values intended only for local demos and tests.
