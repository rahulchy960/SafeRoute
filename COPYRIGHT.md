# Copyright and licensing

Copyright (C) 2026 Rahul Chowdhury.

Source code in this repository is licensed under the GNU Affero General Public License v3.0 only
(SPDX: `AGPL-3.0-only`), except where a file states otherwise. Documentation is under
CC BY-NC-ND 4.0 (see [`docs/LICENSE.md`](docs/LICENSE.md)).

## What is covered by what

| Part of the repository | License | SPDX identifier |
| --- | --- | --- |
| Everything outside `docs/`: `backend/`, `android/`, `moderation/`, `contracts/`, `infra/`, `tools/`, workflows and configuration | GNU AGPL v3.0 only, full text in [`LICENSE`](LICENSE) | `AGPL-3.0-only` |
| Everything under `docs/`: the plan, ADRs, diagrams, prompt logs, runbooks | Creative Commons Attribution-NonCommercial-NoDerivatives 4.0, see [`docs/LICENSE.md`](docs/LICENSE.md) | `CC-BY-NC-ND-4.0` |
| `docs/adr/template.md` | Free to reuse without conditions (exception, see [`docs/LICENSE.md`](docs/LICENSE.md)) | none |
| The names "SafeRoute" and "SafeRoute Kolkata", the app icon and any logo | Not licensed, see [`TRADEMARKS.md`](TRADEMARKS.md) | none |
| Incident reports, user data, location data, moderation data and exports | Not part of this repository and not licensed, see the README section "Data" | none |

New source files carry an `SPDX-License-Identifier: AGPL-3.0-only` header (from P003 onward). A
file without a header is still covered by the table above.

Third-party dependencies keep their own licenses. They are installed by the package managers and
are not part of this repository.

Contributions: see [`CONTRIBUTING.md`](CONTRIBUTING.md).
