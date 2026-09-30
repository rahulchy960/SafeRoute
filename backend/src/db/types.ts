// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import { customType } from 'drizzle-orm/pg-core';

/**
 * A WGS 84 position. Longitude first, as in PostGIS, GeoJSON and ST_MakePoint(x, y).
 * Named fields instead of x/y so a lat/lng swap is visible in code review.
 */
export interface LngLat {
  lng: number;
  lat: number;
}

/** Spatial reference ID of WGS 84 longitude/latitude, the system GPS and map SDKs use. */
export const SRID_WGS84 = 4326;

/**
 * Parses the hex EWKB string pg returns for a geometry(Point) column: byte order (1 byte), type
 * with the SRID flag (uint32), SRID (uint32), then x and y as float64.
 */
export function parseEwkbPoint(hex: string): LngLat {
  const buf = Buffer.from(hex, 'hex');
  const littleEndian = buf.readUInt8(0) === 1;
  const readU32 = (offset: number) =>
    littleEndian ? buf.readUInt32LE(offset) : buf.readUInt32BE(offset);
  const readF64 = (offset: number) =>
    littleEndian ? buf.readDoubleLE(offset) : buf.readDoubleBE(offset);

  const type = readU32(1);
  const hasSrid = (type & 0x20000000) !== 0;
  if ((type & 0xff) !== 1) throw new Error('EWKB value is not a Point');
  const offset = hasSrid ? 9 : 5;
  return { lng: readF64(offset), lat: readF64(offset + 8) };
}

/**
 * Drizzle column type for PostGIS `geometry(Point, 4326)`.
 * Writes use ST_SetSRID(ST_MakePoint(lng, lat), 4326); reads return `{ lng, lat }`.
 * For distances in metres cast to geography or transform to a projected SRID (see src/db/README.md).
 */
export const geometryPoint = customType<{ data: LngLat; driverData: string }>({
  dataType() {
    return `geometry(Point,${String(SRID_WGS84)})`;
  },
  toDriver(value) {
    return sql`ST_SetSRID(ST_MakePoint(${value.lng}, ${value.lat}), ${SRID_WGS84})`;
  },
  fromDriver(value) {
    return parseEwkbPoint(value);
  },
});
