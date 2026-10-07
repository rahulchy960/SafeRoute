#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
#
# Starts osrm-routed in the saferoute-osrm image (P012a, ADR 0020).
#
# --verbosity WARNING is a PRIVACY setting, not a tidiness one: at INFO, osrm-routed writes one
# line per request with the full request path, and that path holds the coordinates of the origin
# and the destination. Never raise it (scripts/local-smoke.mjs fails if a request line appears).
#
# The limits shrink what SafeRoute does not use: a route has two points and at most three
# results. 3 is the smallest size osrm-routed 26.10 starts with for the route, table, trip and
# matching services (with 1 or 2 it exits with code 1 and no message; found by trying).
set -eu

exec osrm-routed \
  --algorithm mld \
  --ip 0.0.0.0 \
  --port "${PORT:-5000}" \
  --threads "${OSRM_THREADS:-2}" \
  --verbosity WARNING \
  --max-viaroute-size 3 \
  --max-alternatives 3 \
  --max-table-size 3 \
  --max-trip-size 3 \
  --max-matching-size 3 \
  --max-nearest-size 1 \
  /graph/routing.osrm
