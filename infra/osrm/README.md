# infra/osrm/

The routing engine images (P012a, [ADR 0020](../../docs/adr/0020-routing-osrm.md)): self-hosted
[OSRM](https://github.com/Project-OSRM/osrm-backend) over OpenStreetMap data, one image per
profile, with the prepared road graph baked in. Measurements, rebuild steps and costs are in
[`docs/runbooks/routing-capacity-staging.md`](../../docs/runbooks/routing-capacity-staging.md).

| File | What it is |
| --- | --- |
| [`Dockerfile`](Dockerfile) | Three stages: cut the extent (osmium) → build the MLD graph (`osrm-extract`, `osrm-partition`, `osrm-customize`) → `osrm-routed` on `$PORT` as an unprivileged user |
| [`config.json`](config.json) | Where extracts may come from, the data licence and credit, the profiles and the extents |
| [`scripts/build-image.mjs`](scripts/build-image.mjs) | Downloads and checks the extract, writes `metadata.json`, builds one image |
| [`scripts/local-smoke.mjs`](scripts/local-smoke.mjs) | Starts an image with Docker and checks routes, limits, the user, the labels and the log |
| [`scripts/measure.mjs`](scripts/measure.mjs) | Latency and failure rates over random, seeded points |
| [`scripts/make-extent.mjs`](scripts/make-extent.mjs) | Makes [`extents/state-extent.json`](extents/state-extent.json), the simplified outline the API will use (P012b) |
| [`scripts/cut-extent.sh`](scripts/cut-extent.sh), [`scripts/entrypoint.sh`](scripts/entrypoint.sh) | Run inside the image build and the container |
| [`sample/`](sample/) | A 79 KB OpenStreetMap fixture (roads only, about 3 km across, around the Maidan in Kolkata) and pairs of public places for the checks |

```sh
# The whole pipeline on the fixture, as CI runs it (a few seconds):
node infra/osrm/scripts/build-image.mjs --profile foot --extent sample
node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:foot-sample-2026-10-06 --profile foot

# The state (downloads about 250 MB once, needs about 4 GB of memory, about 3 minutes):
node infra/osrm/scripts/build-image.mjs --profile car --extent state \
  --extract-url https://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf --extract-date 2026-10-06
node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:car-state-2026-10-06 --profile car --pairs state
```

**Rules:**

- **Never raise the log level.** At OSRM's default level, `osrm-routed` writes every request
  path, and the path holds the origin and the destination. `entrypoint.sh` sets `WARNING`, and
  the smoke test fails if a request path or a coordinate appears in the container log.
- **No routing data in git.** Extracts and graphs live in `infra/osrm/data/` (ignored) and in
  images. The only exception is the fixture in `sample/`; `osrm-ci` checks this.
- **One build at a time**: every build uses `infra/osrm/data/source.osm.pbf` as its input.
- The stock `foot` and `car` profiles are used as shipped. No custom Lua (ADR 0020).
- The services are private. Nothing here is ever exposed to the internet or to the app; only
  the API calls OSRM. Deploying: [`osrm-staging`](../../.github/workflows/osrm-staging.yml),
  by hand, after `bootstrap-staging.ps1 -Apply` (runbook, section 5).

**Data licence.** The extract, the fixture, `extents/state-extent.json` and every graph are
derived from OpenStreetMap: © OpenStreetMap contributors, available under the
[Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
(<https://www.openstreetmap.org/copyright>). They are not covered by this repository's AGPL
licence. OSRM itself is BSD-2-Clause.
