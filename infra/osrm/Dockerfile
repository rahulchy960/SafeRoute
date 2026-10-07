# SPDX-License-Identifier: AGPL-3.0-only
#
# saferoute-osrm container image (P012a, ADR 0020): one routing profile over one extent, with the
# prepared road graph baked in. Build context: infra/osrm/. Build it with the script, which
# downloads and checks the OpenStreetMap extract and fills in the arguments and labels:
#
#   node infra/osrm/scripts/build-image.mjs --profile foot --extent sample
#
# Three stages:
#   extent  cuts the configured extent out of the downloaded extract (osmium)
#   graph   osrm-extract → osrm-partition → osrm-customize (MLD), then drops build-only files
#   runtime osrm-routed on $PORT as an unprivileged user, quiet logs, tight request limits
#
# The extract is OpenStreetMap data (ODbL 1.0), not part of this repository's licence. It reaches
# the build through a bind mount and is never copied into a layer; only the prepared graph is.

# OSRM 26.10.0 (latest stable on 2026-10-07) and Debian 13 slim, pinned by tag AND digest.
# To update: `docker buildx imagetools inspect <image>:<tag>`, change both parts, rerun
# scripts/local-smoke.mjs and the measurements in docs/runbooks/routing-capacity-staging.md.
ARG OSRM_IMAGE=ghcr.io/project-osrm/osrm-backend:v26.10.0-debian@sha256:55dbfd47d984c2cacd64e32901b4321c86b5ec761438e69aa053dc78698a786f
ARG TOOLS_IMAGE=debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

# ── extent: cut the area out of the extract ───────────────────────────────────────────────────
FROM ${TOOLS_IMAGE} AS extent
RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool=1.18.0-1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
COPY scripts/cut-extent.sh /usr/local/bin/cut-extent.sh
# relation: an OSM boundary relation id found in the extract · bbox: minLon,minLat,maxLon,maxLat
# none: use the extract as it is (the sample fixture)
ARG EXTENT_KIND=none
ARG EXTENT_VALUE=
RUN --mount=type=bind,source=data/source.osm.pbf,target=/in/source.osm.pbf \
    sh /usr/local/bin/cut-extent.sh /in/source.osm.pbf /work/extent.osm.pbf "${EXTENT_KIND}" "${EXTENT_VALUE}"

# ── graph: prepare the routing graph ──────────────────────────────────────────────────────────
FROM ${OSRM_IMAGE} AS graph
# foot or car: the stock profiles shipped in the OSRM image (/opt/<profile>.lua). No custom Lua.
ARG PROFILE=foot
WORKDIR /graph
# The .cnbg, .ebg, .enw, .restrictions and .turn_penalties_index files are needed by the build
# steps only (`osrm-routed --list-inputs` names what the server reads); they are a fifth of the size.
# osrm-extract writes .fileIndex readable by its owner only, hence the chmod.
RUN --mount=type=bind,from=extent,source=/work/extent.osm.pbf,target=/in/routing.osm.pbf \
    test -f "/opt/${PROFILE}.lua" \
    && cp /in/routing.osm.pbf /graph/routing.osm.pbf \
    && osrm-extract --verbosity WARNING -p "/opt/${PROFILE}.lua" /graph/routing.osm.pbf \
    && rm /graph/routing.osm.pbf \
    && osrm-partition --verbosity WARNING /graph/routing.osrm \
    && osrm-customize --verbosity WARNING /graph/routing.osrm \
    && rm -f /graph/routing.osrm.cnbg /graph/routing.osrm.cnbg_to_ebg /graph/routing.osrm.ebg \
       /graph/routing.osrm.enw /graph/routing.osrm.restrictions /graph/routing.osrm.turn_penalties_index \
    && chmod -R u=rwX,go=rX /graph \
    && osrm-routed --algorithm mld --trial=on /graph/routing.osrm

# ── runtime: what is deployed ─────────────────────────────────────────────────────────────────
FROM ${OSRM_IMAGE} AS runtime
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin osrm
# osrm-routed maps .fileIndex read-write (it does not change it), so the graph belongs to the
# unprivileged user. Cloud Run keeps such writes in the instance, never in the image.
COPY --from=graph --chown=10001:10001 /graph /graph
COPY scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
# Written by build-image.mjs: where the data came from, its date, checksum, licence and credit.
COPY data/metadata.json /graph/metadata.json

USER 10001
ENV PORT=5000 \
    OSRM_THREADS=2
EXPOSE 5000
ENTRYPOINT ["sh", "/usr/local/bin/entrypoint.sh"]

LABEL org.opencontainers.image.title="saferoute-osrm" \
      org.opencontainers.image.source="https://github.com/rahulchy960/SafeRoute" \
      org.opencontainers.image.licenses="AGPL-3.0-only AND BSD-2-Clause AND ODbL-1.0"
