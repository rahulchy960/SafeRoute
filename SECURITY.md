# Security policy

SafeRoute Kolkata is a navigation and personal-safety app. It handles **safety-critical and
personal data**: emergency SOS alerts, live location, emergency contacts' phone numbers and
community incident reports. A vulnerability here can put a person at risk, so please report it
privately.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a vulnerability.**

Use GitHub private vulnerability reporting:

1. Open the repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Describe the issue, the affected area (file, endpoint or screen), steps to reproduce and the
   impact you expect. Please don't include real people's personal data in the report.

The report is visible only to the maintainer. You will get an acknowledgement, and the fix and
disclosure are coordinated with you through the private advisory.

## Scope

In scope:

- Code in this repository: `backend/`, `android/`, `moderation/`, `contracts/`, `infra/`,
  `tools/`, and the GitHub Actions workflows in `.github/`.
- Especially: SOS and live-location flows, authentication and authorisation, exposure of personal
  data (locations, phone numbers, report text), capability tokens in share links, rate-limit and
  abuse-control bypasses, and secrets committed to the repository.

Out of scope:

- Third-party services themselves (GitHub, Google Cloud, Firebase, map and routing providers).
  Report those to the vendor.
- Denial-of-service by volume, social engineering and physical attacks.
- Findings that need a rooted or compromised device, unless the app makes the impact worse.

## Safe-harbour expectations

Test only against your own accounts and data. Don't access, change or keep other people's data,
don't send SOS or share alerts to real people, and stop and report as soon as you find personal
data.

SafeRoute is **not an emergency service**. If you or someone else is in danger, call **112**.

## Supported versions

The project is pre-release. Only the latest `main` branch is supported.
