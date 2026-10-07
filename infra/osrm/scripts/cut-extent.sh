#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
#
# Cuts the routing extent out of an OpenStreetMap extract (P012a, ADR 0020). Runs inside the
# "extent" stage of infra/osrm/Dockerfile.
#
#   cut-extent.sh <source.osm.pbf> <output.osm.pbf> <relation|bbox|none> <value>
#
#   relation  <value> is the id of an administrative boundary relation that the extract contains.
#             The polygon is assembled from the extract itself (no other service is asked) and
#             used unsimplified.
#   bbox      <value> is minLon,minLat,maxLon,maxLat.
#   none      the extract is used as it is.
#
# Strategy "smart" keeps every way that crosses the edge whole, so roads are not cut in the
# middle and a route can leave the area for a few hundred metres and come back.
set -eu

source_file="$1"
output_file="$2"
kind="$3"
value="${4:-}"

case "$kind" in
  none)
    cp "$source_file" "$output_file"
    ;;
  bbox)
    if ! printf '%s' "$value" | grep -Eq '^-?[0-9.]+,-?[0-9.]+,-?[0-9.]+,-?[0-9.]+$'; then
      echo "cut-extent: a bbox must be minLon,minLat,maxLon,maxLat" >&2
      exit 1
    fi
    osmium extract --strategy smart --bbox "$value" --output "$output_file" "$source_file"
    ;;
  relation)
    if ! printf '%s' "$value" | grep -Eq '^[0-9]+$'; then
      echo "cut-extent: a relation id must be a number" >&2
      exit 1
    fi
    osmium getid --add-referenced --remove-tags --output boundary.osm.pbf "$source_file" "r${value}"
    osmium export --geometry-types=polygon --output-format geojson --output boundary.geojson boundary.osm.pbf
    features="$(grep -o '"type":"Feature"' boundary.geojson | wc -l)"
    if [ "$features" -ne 1 ]; then
      echo "cut-extent: relation ${value} gave ${features} polygons, expected 1 (is it complete in this extract?)" >&2
      exit 1
    fi
    osmium extract --strategy smart --polygon boundary.geojson --output "$output_file" "$source_file"
    rm boundary.osm.pbf boundary.geojson
    ;;
  *)
    echo "cut-extent: unknown extent kind '${kind}'" >&2
    exit 1
    ;;
esac

if [ ! -s "$output_file" ]; then
  echo "cut-extent: the cut is empty" >&2
  exit 1
fi
