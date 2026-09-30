// SPDX-License-Identifier: AGPL-3.0-only
/**
 * PostGIS de-risking spike (Plan v7 §10). Test-only: creates a TEMPORARY table (dropped with the
 * session), never a product table. Positions are public landmarks in Kolkata.
 */
import { sql } from 'drizzle-orm';
import { drizzle } from 'drizzle-orm/node-postgres';
import { index, pgTable, serial, text } from 'drizzle-orm/pg-core';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { geometryPoint, parseEwkbPoint, type LngLat } from '../../src/db/types.js';
import { databaseUrl } from './helpers.js';

const spikePlaces = pgTable(
  'spike_places',
  {
    id: serial('id').primaryKey(),
    name: text('name').notNull(),
    location: geometryPoint('location').notNull(),
  },
  (t) => [index('spike_places_location_gist').using('gist', t.location)],
);

// Longitude first. Kolkata is ~88.36° E, ~22.57° N; a swap would put the point in the Arabian Sea.
const VICTORIA_MEMORIAL: LngLat = { lng: 88.3426, lat: 22.5448 };
const NEARBY_300M: LngLat = { lng: 88.3455, lat: 22.5448 }; // ~300 m east
const HOWRAH_BRIDGE: LngLat = { lng: 88.3468, lat: 22.5851 }; // ~4.5 km north

// A single client: temporary tables live only in the session that created them.
const client = new pg.Client({ connectionString: databaseUrl() });
const db = drizzle(client);

beforeAll(async () => {
  await client.connect();
  await db.execute(sql`
    create temporary table spike_places (
      id serial primary key,
      name text not null,
      location geometry(Point, 4326) not null
    )`);
  await db.execute(
    sql`create index spike_places_location_gist on spike_places using gist (location)`,
  );
  await db.insert(spikePlaces).values([
    { name: 'victoria-memorial', location: VICTORIA_MEMORIAL },
    { name: 'nearby-300m', location: NEARBY_300M },
    { name: 'howrah-bridge', location: HOWRAH_BRIDGE },
  ]);
});

afterAll(async () => {
  await client.end(); // drops the temporary table
});

describe('PostGIS spike', () => {
  it('stores lng/lat in x/y order and reads them back through the custom type', async () => {
    const rows = await db
      .select({
        location: spikePlaces.location,
        x: sql<number>`ST_X(${spikePlaces.location})`,
        y: sql<number>`ST_Y(${spikePlaces.location})`,
        srid: sql<number>`ST_SRID(${spikePlaces.location})`,
      })
      .from(spikePlaces)
      .where(sql`${spikePlaces.name} = 'victoria-memorial'`);
    expect(rows[0]).toEqual({
      location: VICTORIA_MEMORIAL,
      x: VICTORIA_MEMORIAL.lng,
      y: VICTORIA_MEMORIAL.lat,
      srid: 4326,
    });
  });

  it('parses EWKB in both byte orders', () => {
    // POINT(0 1) with SRID 4326: little-endian (what PostGIS returns on x86) and big-endian.
    const le = '0101000020E6100000' + '0000000000000000' + '000000000000F03F';
    const be = '0020000001000010E6' + '0000000000000000' + '3FF0000000000000';
    expect(parseEwkbPoint(le)).toEqual({ lng: 0, lat: 1 });
    expect(parseEwkbPoint(be)).toEqual({ lng: 0, lat: 1 });
  });

  it('finds points within a radius in metres with ST_DWithin on geography', async () => {
    const rows = await db
      .select({ name: spikePlaces.name })
      .from(spikePlaces)
      .where(
        sql`ST_DWithin(${spikePlaces.location}::geography,
          ST_SetSRID(ST_MakePoint(${VICTORIA_MEMORIAL.lng}, ${VICTORIA_MEMORIAL.lat}), 4326)::geography,
          500)`,
      )
      .orderBy(spikePlaces.name);
    expect(rows.map((r) => r.name)).toEqual(['nearby-300m', 'victoria-memorial']);
  });

  it('buffers a route by 75 m in UTM zone 45N (EPSG:32645) and intersects points', async () => {
    // A short north-south line ~40 m west of the Victoria Memorial point.
    const route = sql`ST_SetSRID(ST_MakeLine(
      ST_MakePoint(88.3422, 22.5400), ST_MakePoint(88.3422, 22.5500)), 4326)`;
    const buffer75m = sql`ST_Buffer(ST_Transform(${route}, 32645), 75)`;

    const rows = await db
      .select({ name: spikePlaces.name })
      .from(spikePlaces)
      .where(sql`ST_Intersects(${buffer75m}, ST_Transform(${spikePlaces.location}, 32645))`);
    expect(rows.map((r) => r.name)).toEqual(['victoria-memorial']);

    const area = await db.execute<{ m2: number }>(sql`select ST_Area(${buffer75m}) as m2`);
    // ~1.1 km line x 150 m wide plus round caps: roughly 1.8e5 m².
    expect(Number(area.rows[0]?.m2)).toBeGreaterThan(1.5e5);
    expect(Number(area.rows[0]?.m2)).toBeLessThan(2.1e5);
  });

  it('created a GiST index on the geometry column', async () => {
    const { rows } = await client.query<{ indexdef: string }>(
      `select indexdef from pg_indexes where indexname = 'spike_places_location_gist'`,
    );
    expect(rows[0]?.indexdef).toMatch(/USING gist \(location\)/);
  });

  it('leaves no objects behind in the public schema', async () => {
    const other = new pg.Client({ connectionString: databaseUrl() });
    await other.connect();
    const { rows } = await other.query(
      `select 1 from pg_tables where schemaname = 'public' and tablename like 'spike%'`,
    );
    await other.end();
    expect(rows).toHaveLength(0);
  });
});
