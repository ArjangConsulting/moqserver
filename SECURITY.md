# Security Policy

## Supported Versions

Security fixes are handled on the `main` branch until formal release branches exist.

## Reporting a Vulnerability

Please do not open public issues for suspected vulnerabilities.

Report privately using GitHub Security Advisories if available for this repository. If advisories are not enabled, contact the maintainers through the project owner profile and include enough detail to reproduce the issue.

Helpful details include:

- affected command or app workflow
- input files needed to reproduce the issue
- expected and actual behavior
- whether credentials, imported HAR data, or generated mock output are involved

## Secrets and Test Data

Do not include real API keys, bearer tokens, cookies, HAR captures with live credentials, private OpenAPI specs, or production `.moqproj` bundles in issues or pull requests.

The sample credentials in this repository are mock values intended only for local demos and tests.
